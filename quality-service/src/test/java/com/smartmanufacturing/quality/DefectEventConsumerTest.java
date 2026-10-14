package com.smartmanufacturing.quality;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.apache.rocketmq.common.message.MessageExt;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import static org.assertj.core.api.Assertions.assertThat;

/** 消费入口的 ACK 判定（#30，H2，不连 Broker）：坏消息留档 + 计 invalid + ACK；合法消息行为不变。 */
@SpringBootTest
@ActiveProfiles("test")
class DefectEventConsumerTest {
    @Autowired DefectEventHandler handler;
    @Autowired InvalidEventRecorder recorder;
    @Autowired QualityMetrics metrics;
    @Autowired MqProperties mq;
    @Autowired ObjectMapper mapper;
    @Autowired JdbcTemplate jdbc;
    @Autowired MeterRegistry registry;
    @Autowired RecordingEventSender sender;

    @BeforeEach void isolate() { QualityTestIsolation.reset(jdbc, sender, null); }

    private DefectEventConsumer consumer() {
        // 不调用 start()：只验证单条消息的处理与 ACK 判定。
        return new DefectEventConsumer(handler, recorder, metrics, mq, mapper, "unused:9876", 1);
    }

    private static MessageExt message(String tag, String body) {
        MessageExt m = new MessageExt();
        m.setTopic("mes-workorder-quality");
        m.setTags(tag);
        m.setBody(body.getBytes(StandardCharsets.UTF_8));
        m.setMsgId("MSG-" + UUID.randomUUID());
        return m;
    }

    private double count(String tag, String result) {
        Counter c = registry.find("mes.quality.event.consumed").tag("tag", tag).tag("result", result).counter();
        return c == null ? 0 : c.count();
    }

    private int recorded(String msgId) {
        return jdbc.queryForObject("select count(*) from invalid_event where msg_id = ?", Integer.class, msgId);
    }

    @Test void malformedJsonIsRecordedAndAcked() {
        MessageExt m = message("DefectReported", "{not json");
        double before = count("DefectReported", "invalid");
        assertThat(consumer().consume(m)).isTrue();
        assertThat(recorded(m.getMsgId())).isEqualTo(1);
        assertThat(count("DefectReported", "invalid")).isEqualTo(before + 1);
        assertThat(jdbc.queryForObject("select reason from invalid_event where msg_id = ?", String.class, m.getMsgId())).contains("JSON");
    }

    @Test void missingEventIdIsRecordedAndAcked() throws Exception {
        var body = DefectEvents.reported("x", "RPT-" + UUID.randomUUID(), "WO-C", "1", "件");
        body.remove("eventId");
        MessageExt m = message("DefectReported", body.toString());
        assertThat(consumer().consume(m)).isTrue();
        assertThat(jdbc.queryForObject("select reason from invalid_event where msg_id = ?", String.class, m.getMsgId())).contains("eventId");
        assertThat(jdbc.queryForObject("select payload_bytes from invalid_event where msg_id = ?", Integer.class, m.getMsgId()))
            .isEqualTo(m.getBody().length);
    }

    @Test void longPayloadIsExcerpted() {
        MessageExt m = message("DefectReversed", "x".repeat(5000));
        assertThat(consumer().consume(m)).isTrue();
        assertThat(jdbc.queryForObject("select length(payload_excerpt) from invalid_event where msg_id = ?", Integer.class, m.getMsgId()))
            .isEqualTo(InvalidEventRecorder.EXCERPT_CHARS);
    }

    @Test void validMessageIsUnchanged() {
        String reportId = "RPT-" + UUID.randomUUID();
        MessageExt m = message("DefectReported", DefectEvents.reported(DefectEvents.id(), reportId, "WO-C", "1", "件").toString());
        double ok = count("DefectReported", "ok");
        assertThat(consumer().consume(m)).isTrue();
        assertThat(count("DefectReported", "ok")).isEqualTo(ok + 1);
        assertThat(recorded(m.getMsgId())).isZero();
        assertThat(jdbc.queryForObject("select count(*) from ncr where source_report_id = ?", Integer.class, reportId)).isEqualTo(1);
    }
}
