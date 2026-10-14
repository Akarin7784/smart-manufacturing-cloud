package com.smartmanufacturing.quality;

import com.smartmanufacturing.quality.DefectEventHandler.Result;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import java.time.Duration;
import java.util.UUID;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 不良品事件处理规则（H2，直接调用处理器）：去重、冲销先到暂存与对冲、TTL 过期、已处置冲突、迁移指标 from=NONE。
 * 经 Broker 投递的同一组门禁在 DefectConsumptionRocketMqIT 中以真实 RocketMQ + MySQL 再验证一遍。
 */
@SpringBootTest
@ActiveProfiles("test")
class DefectEventHandlerTest {
    @Autowired DefectEventHandler handler;
    @Autowired PendingReversalExpiryJob expiry;
    @Autowired NcrService ncrs;
    @Autowired IdempotencyService idempotency;
    @Autowired JdbcTemplate jdbc;
    @Autowired MutableClock clock;
    @Autowired MeterRegistry registry;
    @Autowired OutboxPublisher publisher;
    @Autowired RecordingEventSender sender;

    @BeforeEach void setUp() { QualityTestIsolation.reset(jdbc, sender, clock); }
    @AfterEach void tearDown() { clock.reset(); }

    private static String rid() { return "RPT-" + UUID.randomUUID(); }
    private Result report(String eventId, String reportId) {
        return handler.handle("DefectReported", eventId, DefectEvents.reported(eventId, reportId, "WO-U", "5", "件"));
    }
    private Result reverse(String eventId, String reportId, String reversalId) {
        return handler.handle("DefectReversed", eventId, DefectEvents.reversed(eventId, reportId, reversalId));
    }
    private String ncrOf(String reportId) { return jdbc.queryForObject("select ncr_no from ncr where source_report_id = ?", String.class, reportId); }
    private String status(String reportId) { return jdbc.queryForObject("select status from ncr where source_report_id = ?", String.class, reportId); }
    private String pending(String reportId) { return jdbc.queryForObject("select status from pending_reversal where reversed_report_id = ?", String.class, reportId); }
    private double transition(String from, String to) {
        Counter c = registry.find("mes.quality.ncr.transition").tag("from", from).tag("to", to).counter();
        return c == null ? 0 : c.count();
    }
    private double expired() { return registry.get("mes.quality.pending.reversal.expired").counter().count(); }

    @Test void duplicateEventIdCreatesOneNcr() {
        String r = rid(), e = DefectEvents.id();
        double opened = transition("NONE", "OPEN");
        assertThat(report(e, r)).isEqualTo(Result.OK);
        assertThat(report(e, r)).isEqualTo(Result.DUPLICATE);
        // 换了 eventId 的同一报工：source_report_id 唯一，仍只有一张。
        assertThat(report(DefectEvents.id(), r)).isEqualTo(Result.DUPLICATE);
        assertThat(jdbc.queryForObject("select count(*) from ncr where source_report_id = ?", Integer.class, r)).isEqualTo(1);
        assertThat(ncrOf(r)).matches(NcrService.NCR_NO);
        assertThat(transition("NONE", "OPEN")).isEqualTo(opened + 1);
    }

    @Test void reversalBeforeReportWithinTtlCreatesVoidedNcrAndOnlyNcrVoided() {
        String r = rid();
        double before = transition("NONE", "VOIDED");
        assertThat(reverse(DefectEvents.id(), r, "REV-1")).isEqualTo(Result.PENDING);
        assertThat(pending(r)).isEqualTo("WAITING");
        clock.advance(Duration.ofHours(23));
        expiry.expireDue();
        assertThat(pending(r)).isEqualTo("WAITING");
        assertThat(report(DefectEvents.id(), r)).isEqualTo(Result.MATCHED);
        assertThat(status(r)).isEqualTo("VOIDED");
        assertThat(jdbc.queryForObject("select void_reason from ncr where source_report_id = ?", String.class, r)).isEqualTo("REVERSED_BEFORE_REPORTED");
        assertThat(pending(r)).isEqualTo("MATCHED");
        assertThat(jdbc.queryForObject("select ncr_no from pending_reversal where reversed_report_id = ?", String.class, r)).isEqualTo(ncrOf(r));
        assertThat(transition("NONE", "VOIDED")).isEqualTo(before + 1);
        publisher.publishBatch();
        assertThat(sender.sent()).extracting(RecordingEventSender.Sent::tag).containsExactly("NcrVoided");
    }

    @Test void pendingReversalExpiresAfterTtlAndLateReportOpensNcr() {
        String r = rid();
        reverse(DefectEvents.id(), r, "REV-2");
        double before = expired();
        clock.advance(Duration.ofHours(24).plusSeconds(1));
        // expireDue() 与过期计数器都是全局的：只断言本用例自己的暂存行，计数器至少 +1。
        assertThat(expiry.expireDue()).isGreaterThanOrEqualTo(1);
        assertThat(pending(r)).isEqualTo("EXPIRED");
        assertThat(expired()).isGreaterThanOrEqualTo(before + 1);
        expiry.expireDue();
        assertThat(pending(r)).isEqualTo("EXPIRED");
        // 过期后原报工才到：建 OPEN 的 NCR，暂存保持 EXPIRED。
        assertThat(report(DefectEvents.id(), r)).isEqualTo(Result.OK);
        assertThat(status(r)).isEqualTo("OPEN");
        assertThat(pending(r)).isEqualTo("EXPIRED");
        // 同一报工再来一条冲销：NCR 未处置，正常作废。
        assertThat(reverse(DefectEvents.id(), r, "REV-2")).isEqualTo(Result.OK);
        assertThat(status(r)).isEqualTo("VOIDED");
    }

    @Test void reversalOfUndisposedNcrVoidsIt() {
        String r = rid();
        report(DefectEvents.id(), r);
        double before = transition("OPEN", "VOIDED");
        assertThat(reverse(DefectEvents.id(), r, "REV-3")).isEqualTo(Result.OK);
        assertThat(status(r)).isEqualTo("VOIDED");
        assertThat(transition("OPEN", "VOIDED")).isEqualTo(before + 1);
        assertThat(jdbc.queryForObject("select version from ncr where source_report_id = ?", Long.class, r)).isEqualTo(2);
        // 已作废后再收到冲销（不同 eventId）：视为重复，不再变化。
        assertThat(reverse(DefectEvents.id(), r, "REV-3")).isEqualTo(Result.DUPLICATE);
    }

    @Test void reversalOfDisposedNcrRecordsConflictAndKeepsNcr() {
        String r = rid();
        report(DefectEvents.id(), r);
        String ncrNo = ncrOf(r);
        idempotency.execute("qa", UUID.randomUUID().toString(), "ncr.review", ncrNo, 200, () -> ncrs.review(ncrNo));
        idempotency.execute("qa", UUID.randomUUID().toString(), "ncr.dispose", ncrNo, 201,
            () -> ncrs.dispose(ncrNo, "qa", new NcrDtos.CreateDisposition("SCRAP", "2", "件", null)));
        String e = DefectEvents.id();
        assertThat(reverse(e, r, "REV-4")).isEqualTo(Result.DISPOSED_REVERSAL_CONFLICT);
        assertThat(status(r)).isEqualTo("UNDER_REVIEW");
        assertThat(jdbc.queryForObject("select disposed_qty from ncr where ncr_no = ?", java.math.BigDecimal.class, ncrNo)).isEqualByComparingTo("2");
        assertThat(jdbc.queryForObject("select count(*) from ncr_conflict where ncr_no = ?", Integer.class, ncrNo)).isEqualTo(1);
        assertThat(reverse(e, r, "REV-4")).isEqualTo(Result.DUPLICATE);
        assertThat(reverse(DefectEvents.id(), r, "REV-4")).isEqualTo(Result.DUPLICATE);
        assertThat(jdbc.queryForObject("select count(*) from ncr_conflict where ncr_no = ?", Integer.class, ncrNo)).isEqualTo(1);
    }

    @Test void invalidEventsAreRejectedWithoutSideEffects() {
        String e = DefectEvents.id();
        var bad = DefectEvents.reported(e, rid(), "WO-U", "abc", "件");
        assertThatThrownBy(() -> handler.handle("DefectReported", e, bad)).isInstanceOf(DefectEventHandler.InvalidEventException.class);
        assertThat(jdbc.queryForObject("select count(*) from consumed_event where event_id = ?", Integer.class, e)).isZero();
        assertThatThrownBy(() -> handler.handle("Other", e, bad)).isInstanceOf(DefectEventHandler.InvalidEventException.class);
        assertThatThrownBy(() -> handler.handle("DefectReported", null, bad)).isInstanceOf(DefectEventHandler.InvalidEventException.class);
        var noReversal = DefectEvents.reversed(e, rid(), "R");
        noReversal.remove("reversalId");
        assertThatThrownBy(() -> handler.handle("DefectReversed", e, noReversal)).isInstanceOf(DefectEventHandler.InvalidEventException.class);
    }

    /**
     * #46 回归：时钟固定在亚毫秒时刻（.000600），写入后同一时刻立即发布，事件必须被选中。
     * 修复前 next_attempt_at 会被 TIMESTAMP(3) 舍入为 .001，晚于发布时刻而被跳过（Windows 上间歇失败）。
     */
    @Test void eventWrittenAndPublishedInSameMillisecondIsDue() {
        clock.pin(java.time.Instant.parse("2026-10-12T04:00:00.000600Z"));
        report(DefectEvents.id(), rid());
        assertThat(publisher.publishBatch()).isEqualTo(1);
        assertThat(sender.sent()).extracting(RecordingEventSender.Sent::tag).containsExactly("NcrOpened");
    }

    @Test void outboxStopsBatchOnFirstFailureAndKeepsEventId() {
        report(DefectEvents.id(), rid());
        report(DefectEvents.id(), rid());
        sender.failNext(1);
        assertThat(publisher.publishBatch()).isZero();
        assertThat(sender.attempts()).isEqualTo(1);
        String failedId = jdbc.queryForObject("select event_id from outbox_event where attempts = 1 and status = 'NEW'", String.class);
        clock.advance(Duration.ofSeconds(2));
        assertThat(publisher.publishBatch()).isEqualTo(2);
        assertThat(sender.sent()).extracting(RecordingEventSender.Sent::eventId).contains(failedId);
    }

    @Test void ncrNumbersAreDailySequences() {
        String a = rid(), b = rid();
        report(DefectEvents.id(), a);
        report(DefectEvents.id(), b);
        String na = ncrOf(a), nb = ncrOf(b);
        assertThat(na.substring(0, 13)).isEqualTo(nb.substring(0, 13));
        assertThat(Integer.parseInt(nb.substring(13))).isEqualTo(Integer.parseInt(na.substring(13)) + 1);
    }
}
