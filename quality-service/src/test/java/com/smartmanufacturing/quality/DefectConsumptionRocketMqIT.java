package com.smartmanufacturing.quality;

import com.fasterxml.jackson.databind.node.ObjectNode;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.apache.rocketmq.client.producer.DefaultMQProducer;
import org.apache.rocketmq.client.producer.SendStatus;
import org.apache.rocketmq.common.message.Message;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * docs/quality-design.md 第 6 节合入门槛 1–3：真实 RocketMQ（经 Broker 投递到 CG_QUALITY_DEFECT）+ 真实 MySQL 8.4。
 * 测试扮演工单服务，用 remoting 生产者向 mes-workorder-quality 发送 DefectReported / DefectReversed（eventId 同时放消息体与属性），
 * 由应用内的 {@link DefectEventConsumer} 拉取处理；断言均以数据库状态与消费指标为准。
 */
@Testcontainers
@SpringBootTest(properties = "quality.consumer.enabled=true")
@ActiveProfiles("test")
class DefectConsumptionRocketMqIT {
    @Container @ServiceConnection
    static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.4");

    private static DefaultMQProducer producer;

    @DynamicPropertySource
    static void rocketmq(DynamicPropertyRegistry registry) {
        registry.add("rocketmq.name-server", RocketMqBroker::nameServer);
    }

    @Autowired JdbcTemplate jdbc;
    @Autowired MutableClock clock;
    @Autowired PendingReversalExpiryJob expiry;
    @Autowired NcrService ncrs;
    @Autowired IdempotencyService idempotency;
    @Autowired MeterRegistry registry;

    @BeforeAll static void startProducer() throws Exception {
        producer = new DefaultMQProducer("PG_IT_WORKORDER_QUALITY");
        producer.setNamesrvAddr(RocketMqBroker.nameServer());
        producer.setSendMsgTimeout(10000);
        producer.start();
    }

    @AfterAll static void stopProducer() { if (producer != null) producer.shutdown(); }

    @BeforeEach void resetClock() { clock.reset(); }

    /** 以工单服务的方式发送：topic mes-workorder-quality，tag 为事件类型，key 为报工 ID，eventId 放消息属性。 */
    private static void send(String tag, String key, ObjectNode body) throws Exception {
        Message m = new Message("mes-workorder-quality", tag, key, body.toString().getBytes(StandardCharsets.UTF_8));
        m.putUserProperty("eventId", body.get("eventId").asText());
        assertThat(producer.send(m).getSendStatus()).isEqualTo(SendStatus.SEND_OK);
    }

    private double consumed(String tag, String result) {
        Counter c = registry.find("mes.quality.event.consumed").tag("tag", tag).tag("result", result).counter();
        return c == null ? 0 : c.count();
    }
    private double consumedAll(String tag) {
        return registry.find("mes.quality.event.consumed").tag("tag", tag).counters().stream().mapToDouble(Counter::count).sum();
    }
    private int count(String sql, Object... args) { return jdbc.queryForObject(sql, Integer.class, args); }
    private String ncrStatus(String reportId) {
        List<String> s = jdbc.queryForList("select status from ncr where source_report_id = ?", String.class, reportId);
        return s.isEmpty() ? null : s.get(0);
    }
    private String pendingStatus(String reportId) {
        List<String> s = jdbc.queryForList("select status from pending_reversal where reversed_report_id = ?", String.class, reportId);
        return s.isEmpty() ? null : s.get(0);
    }
    private static String rid() { return "RPT-IT-" + UUID.randomUUID(); }
    private static Duration timeout() { return Duration.ofSeconds(60); }

    /** 门禁 1：同一 DefectReported（eventId 相同）经 Broker 投递 3 次，只生成 1 张 NCR，另两次计 duplicate。 */
    @Test void sameEventDeliveredThreeTimesCreatesOneNcr() throws Exception {
        String reportId = rid();
        ObjectNode body = DefectEvents.reported(DefectEvents.id(), reportId, "WO-IT-1", "3", "件");
        double ok = consumed("DefectReported", "ok"), dup = consumed("DefectReported", "duplicate");
        for (int i = 0; i < 3; i++) send("DefectReported", reportId, body);
        await().atMost(timeout()).until(() -> consumed("DefectReported", "ok") - ok + consumed("DefectReported", "duplicate") - dup >= 3);
        assertThat(count("select count(*) from ncr where source_report_id = ?", reportId)).isEqualTo(1);
        assertThat(consumed("DefectReported", "ok")).isEqualTo(ok + 1);
        assertThat(consumed("DefectReported", "duplicate")).isEqualTo(dup + 2);
        assertThat(count("select count(*) from outbox_event e join ncr n on e.aggregate_id = n.ncr_no where n.source_report_id = ? and e.event_type = 'NcrOpened'",
            reportId)).isEqualTo(1);
    }

    /** 门禁 2a：冲销先到（暂存 WAITING），原报工在 TTL 内到达 → NCR 直接 VOIDED，暂存 MATCHED，只发 NcrVoided。 */
    @Test void reversalBeforeReportWithinTtlMatches() throws Exception {
        String reportId = rid();
        double pending = consumed("DefectReversed", "pending"), matched = consumed("DefectReported", "matched");
        send("DefectReversed", reportId, DefectEvents.reversed(DefectEvents.id(), reportId, "REV-IT-2A"));
        await().atMost(timeout()).until(() -> "WAITING".equals(pendingStatus(reportId)));
        assertThat(consumed("DefectReversed", "pending")).isEqualTo(pending + 1);
        send("DefectReported", reportId, DefectEvents.reported(DefectEvents.id(), reportId, "WO-IT-2A", "2", "件"));
        await().atMost(timeout()).until(() -> "VOIDED".equals(ncrStatus(reportId)));
        assertThat(pendingStatus(reportId)).isEqualTo("MATCHED");
        assertThat(jdbc.queryForObject("select void_reason from ncr where source_report_id = ?", String.class, reportId)).isEqualTo("REVERSED_BEFORE_REPORTED");
        assertThat(consumed("DefectReported", "matched")).isEqualTo(matched + 1);
        assertThat(jdbc.queryForList("select e.event_type from outbox_event e join ncr n on e.aggregate_id = n.ncr_no where n.source_report_id = ?",
            String.class, reportId)).containsExactly("NcrVoided");
    }

    /**
     * 门禁 2b/2c：只有冲销时用可注入 Clock 推进超过 TTL → 暂存 EXPIRED，过期计数 +1；
     * 之后原报工才到 → 生成 OPEN 的 NCR（发 NcrOpened），暂存保持 EXPIRED，不自动对冲。
     */
    @Test void pendingReversalExpiresThenLateReportOpensNcr() throws Exception {
        String reportId = rid();
        send("DefectReversed", reportId, DefectEvents.reversed(DefectEvents.id(), reportId, "REV-IT-2B"));
        await().atMost(timeout()).until(() -> "WAITING".equals(pendingStatus(reportId)));
        double expired = registry.get("mes.quality.pending.reversal.expired").counter().count();
        clock.advance(expiry.ttl().plusSeconds(1));
        assertThat(expiry.expireDue()).isGreaterThanOrEqualTo(1);
        assertThat(pendingStatus(reportId)).isEqualTo("EXPIRED");
        assertThat(registry.get("mes.quality.pending.reversal.expired").counter().count()).isGreaterThanOrEqualTo(expired + 1);

        send("DefectReported", reportId, DefectEvents.reported(DefectEvents.id(), reportId, "WO-IT-2C", "4", "件"));
        await().atMost(timeout()).until(() -> "OPEN".equals(ncrStatus(reportId)));
        assertThat(pendingStatus(reportId)).isEqualTo("EXPIRED");
        assertThat(jdbc.queryForList("select e.event_type from outbox_event e join ncr n on e.aggregate_id = n.ncr_no where n.source_report_id = ?",
            String.class, reportId)).containsExactly("NcrOpened");
    }

    /**
     * 门禁 3：已处置的 NCR 被冲销 → NCR 状态与处置量不变，1 条 DISPOSED_REVERSAL_CONFLICT，冲突计数 +1，
     * 消息已 ACK（只被处理一次、无 error、无重投）。
     */
    @Test void reversalOfDisposedNcrRecordsConflictAndAcks() throws Exception {
        String reportId = rid();
        send("DefectReported", reportId, DefectEvents.reported(DefectEvents.id(), reportId, "WO-IT-3", "5", "件"));
        await().atMost(timeout()).until(() -> "OPEN".equals(ncrStatus(reportId)));
        String ncrNo = jdbc.queryForObject("select ncr_no from ncr where source_report_id = ?", String.class, reportId);
        idempotency.execute("qa-it", UUID.randomUUID().toString(), "ncr.review", ncrNo, 200, () -> ncrs.review(ncrNo));
        idempotency.execute("qa-it", UUID.randomUUID().toString(), "ncr.dispose", ncrNo, 201,
            () -> ncrs.dispose(ncrNo, "qa-it", new NcrDtos.CreateDisposition("REWORK", "5", "件", "返工")));
        assertThat(ncrStatus(reportId)).isEqualTo("DISPOSED");
        long version = jdbc.queryForObject("select version from ncr where ncr_no = ?", Long.class, ncrNo);

        double conflict = consumed("DefectReversed", "disposed_reversal_conflict");
        double errors = consumed("DefectReversed", "error");
        double all = consumedAll("DefectReversed");
        send("DefectReversed", reportId, DefectEvents.reversed(DefectEvents.id(), reportId, "REV-IT-3"));
        await().atMost(timeout()).until(() -> count("select count(*) from ncr_conflict where ncr_no = ?", ncrNo) == 1);
        await().atMost(timeout()).until(() -> consumed("DefectReversed", "disposed_reversal_conflict") == conflict + 1);

        assertThat(ncrStatus(reportId)).isEqualTo("DISPOSED");
        assertThat(jdbc.queryForObject("select disposed_qty from ncr where ncr_no = ?", BigDecimal.class, ncrNo)).isEqualByComparingTo("5");
        assertThat(jdbc.queryForObject("select version from ncr where ncr_no = ?", Long.class, ncrNo)).isEqualTo(version);
        assertThat(jdbc.queryForObject("select type from ncr_conflict where ncr_no = ?", String.class, ncrNo)).isEqualTo("DISPOSED_REVERSAL_CONFLICT");
        // ACK 后不会重投：等待超过 RocketMQ 首次重投间隔（10 秒），确认这条消息只被处理过一次且没有 error。
        Thread.sleep(12_000);
        assertThat(consumedAll("DefectReversed")).isEqualTo(all + 1);
        assertThat(consumed("DefectReversed", "error")).isEqualTo(errors);
        assertThat(count("select count(*) from ncr_conflict where ncr_no = ?", ncrNo)).isEqualTo(1);
    }

    /** 直接发送原始字节（模拟坏消息），不带 eventId 属性。 */
    private static String sendRaw(String tag, byte[] body) throws Exception {
        var r = producer.send(new Message("mes-workorder-quality", tag, "BAD", body));
        assertThat(r.getSendStatus()).isEqualTo(SendStatus.SEND_OK);
        return r.getMsgId();
    }

    /**
     * #30：经 Broker 投递缺 eventId 与非 JSON 的坏消息——各只被消费一次、留有 invalid_event 记录、invalid 计数 +1，
     * 直接 ACK 不重投（等待超过首次重投间隔后计数不变，也没有 error）。
     */
    @Test void invalidMessagesAreRecordedAndAckedOnce() throws Exception {
        double invalid = consumed("DefectReported", "invalid"), errors = consumed("DefectReported", "error");
        double all = consumedAll("DefectReported");
        ObjectNode noEventId = DefectEvents.reported("x", rid(), "WO-IT-BAD", "1", "件");
        noEventId.remove("eventId");
        String a = sendRaw("DefectReported", noEventId.toString().getBytes(StandardCharsets.UTF_8));
        String b = sendRaw("DefectReported", "{broken".getBytes(StandardCharsets.UTF_8));
        await().atMost(timeout()).until(() -> consumed("DefectReported", "invalid") >= invalid + 2);
        Thread.sleep(12_000);
        assertThat(consumed("DefectReported", "invalid")).isEqualTo(invalid + 2);
        assertThat(consumed("DefectReported", "error")).isEqualTo(errors);
        assertThat(consumedAll("DefectReported")).isEqualTo(all + 2);
        // 记录的 msgId 为消费端看到的 msgId（与发送返回的 msgId 一致）。
        assertThat(count("select count(*) from invalid_event where msg_id in (?, ?)", a, b)).isEqualTo(2);
        assertThat(jdbc.queryForObject("select reason from invalid_event where msg_id = ?", String.class, a)).contains("eventId");
    }
}
