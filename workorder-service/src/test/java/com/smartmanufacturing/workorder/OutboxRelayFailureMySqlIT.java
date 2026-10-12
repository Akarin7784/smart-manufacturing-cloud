package com.smartmanufacturing.workorder;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.mockito.ArgumentCaptor;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * outbox 中继失败语义（真实 MySQL 8.4）：发送器始终抛异常时，每批只尝试一条；失败行 attempts + 1、按退避推迟，
 * 其余行不尝试、attempts 不变；重发时 eventId 不变；DISCARDED 行永不领取。
 */
@Testcontainers
@SpringBootTest
@ActiveProfiles("test")
class OutboxRelayFailureMySqlIT {
    @Container @ServiceConnection
    static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.4");

    @Autowired WorkOrderOutboxRelay relay;
    @Autowired JdbcTemplate jdbc;
    @MockitoBean EventSender sender;
    @MockitoBean MasterdataClient masterdata;
    @MockitoBean DeviceClient devices;
    @MockitoBean org.springframework.security.oauth2.jwt.JwtDecoder decoder;

    @BeforeEach void setUp() throws Exception {
        ReleaseFixtures.clear(jdbc);
        doThrow(new IllegalStateException("broker 不可用")).when(sender).send(any(), any(), any(), any(), any());
    }

    private String insert(Instant at, String status) {
        String id = UUID.randomUUID().toString();
        jdbc.update("insert into outbox_event (event_id, aggregate_id, event_type, payload, occurred_at, topic, tag, status) values (?, 'WO-1', 'DefectReported', '{}', ?, 'mes-workorder-quality', 'DefectReported', ?)",
            id, Timestamp.from(at), status);
        return id;
    }

    private Map<String, Object> row(String id) { return jdbc.queryForMap("select attempts, next_attempt_at, last_error, status from outbox_event where event_id = ?", id); }

    @Test void alwaysFailingSenderAttemptsOnlyOnePerBatch() throws Exception {
        Instant t0 = Instant.parse("2026-01-01T00:00:00Z");
        String a = insert(t0, "NEW"), b = insert(t0.plusSeconds(1), "NEW"), c = insert(t0.plusSeconds(2), "NEW");
        String discarded = insert(t0.minusSeconds(10), "DISCARDED");

        assertThat(relay.publishBatch()).isZero();
        ArgumentCaptor<String> ids = ArgumentCaptor.forClass(String.class);
        verify(sender, times(1)).send(eq("mes-workorder-quality"), eq("DefectReported"), eq("WO-1"), ids.capture(), eq("{}"));
        assertThat(ids.getValue()).isEqualTo(a);
        assertThat(((Number) row(a).get("attempts")).intValue()).isEqualTo(1);
        assertThat(row(a).get("next_attempt_at")).isNotNull();
        assertThat((String) row(a).get("last_error")).contains("broker 不可用");
        for (String other : List.of(b, c)) {
            assertThat(((Number) row(other).get("attempts")).intValue()).as("同批剩余行不尝试").isZero();
            assertThat(row(other).get("next_attempt_at")).isNull();
        }

        // 第二批：a 尚在退避期内，领取下一条 b；仍然只尝试一条
        clearInvocations(sender);
        relay.publishBatch();
        verify(sender, times(1)).send(any(), any(), any(), ids.capture(), any());
        assertThat(ids.getValue()).isEqualTo(b);
        assertThat(((Number) row(c).get("attempts")).intValue()).isZero();

        // a 退避到期后重发，eventId 不变，attempts 继续累加
        jdbc.update("update outbox_event set next_attempt_at = ? where event_id in (?, ?)", Timestamp.from(Instant.now().minusSeconds(1)), a, b);
        clearInvocations(sender);
        relay.publishBatch();
        verify(sender, times(1)).send(any(), any(), any(), ids.capture(), any());
        assertThat(ids.getValue()).isEqualTo(a);
        assertThat(((Number) row(a).get("attempts")).intValue()).isEqualTo(2);
        // DISCARDED 行从未被领取
        verify(sender, never()).send(any(), any(), any(), eq(discarded), any());
        assertThat(row(discarded).get("status")).isEqualTo("DISCARDED");
    }

    @Test void backoffIsExponentialAndCapped() {
        assertThat(WorkOrderOutboxRelay.backoff(1)).isEqualTo(Duration.ofSeconds(1));
        assertThat(WorkOrderOutboxRelay.backoff(2)).isEqualTo(Duration.ofSeconds(2));
        assertThat(WorkOrderOutboxRelay.backoff(4)).isEqualTo(Duration.ofSeconds(8));
        assertThat(WorkOrderOutboxRelay.backoff(30)).isEqualTo(Duration.ofMinutes(5));
    }
}
