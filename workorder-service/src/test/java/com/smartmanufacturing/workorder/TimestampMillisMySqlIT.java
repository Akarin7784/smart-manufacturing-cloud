package com.smartmanufacturing.workorder;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * V5 毫秒精度回归（真实 MySQL 8.4）：
 * 1. 参与调度 / 排序的时间列均为 TIMESTAMP(3)；
 * 2. 写入时刻带亚毫秒（.000600）时，写入后在同一毫秒内拉批，退避到期的 outbox 事件与释放任务都能被领取。
 *    去掉 Java 侧截断时，MySQL 把 .000600 四舍五入成 .001，晚于同一毫秒的“当前时间”，记录被判为未到期，本测试失败。
 * 时钟用受控的可变时钟固定在指定时刻，不使用 sleep。
 */
@Testcontainers
@SpringBootTest
@ActiveProfiles("test")
class TimestampMillisMySqlIT {
    @Container @ServiceConnection
    static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.4");

    @Autowired JdbcTemplate jdbc;
    @Autowired TransactionTemplate tx;
    @Autowired InventoryClientMetrics metrics;
    @MockitoBean EventSender sender;
    @MockitoBean MaterialReservationPort materials;
    @MockitoBean MasterdataClient masterdata;
    @MockitoBean DeviceClient devices;
    @MockitoBean org.springframework.security.oauth2.jwt.JwtDecoder decoder;

    /** 带亚毫秒部分的基准时刻：.000600 秒，MySQL 四舍五入会进位到 .001。 */
    static final Instant T0 = Instant.parse("2026-01-01T00:00:00.000600Z");

    /** 测试用可变时钟：pin 固定到指定时刻。 */
    static final class PinnedClock extends Clock {
        private volatile Instant now;
        PinnedClock(Instant now) { this.now = now; }
        void pin(Instant at) { this.now = at; }
        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return this; }
        @Override public Instant instant() { return now; }
    }

    @BeforeEach void setUp() { ReleaseFixtures.clear(jdbc); }

    @Test void schedulingColumnsHaveMillisecondPrecision() {
        List<String[]> columns = List.of(
            new String[] {"outbox_event", "occurred_at"}, new String[] {"outbox_event", "published_at"}, new String[] {"outbox_event", "next_attempt_at"},
            new String[] {"wo_reservation_release_task", "next_attempt_at"}, new String[] {"wo_reservation_release_task", "created_at"},
            new String[] {"wo_reservation_release_task", "completed_at"},
            new String[] {"wo_material_reservation", "expires_at"}, new String[] {"wo_material_reservation", "last_renewed_at"},
            new String[] {"work_order_status_log", "created_at"});
        for (String[] c : columns) {
            Integer precision = jdbc.queryForObject("select datetime_precision from information_schema.columns where table_schema = database() and table_name = ? and column_name = ?",
                Integer.class, c[0], c[1]);
            assertThat(precision).as(c[0] + "." + c[1]).isEqualTo(3);
        }
        // 原有约束保持：NOT NULL 不变，审计表默认值改为 CURRENT_TIMESTAMP(3)
        assertThat(jdbc.queryForObject("select is_nullable from information_schema.columns where table_schema = database() and table_name = 'outbox_event' and column_name = 'occurred_at'", String.class)).isEqualTo("NO");
        assertThat(jdbc.queryForObject("select is_nullable from information_schema.columns where table_schema = database() and table_name = 'wo_reservation_release_task' and column_name = 'next_attempt_at'", String.class)).isEqualTo("YES");
        assertThat(jdbc.queryForObject("select column_default from information_schema.columns where table_schema = database() and table_name = 'work_order_status_log' and column_name = 'created_at'", String.class))
            .containsIgnoringCase("CURRENT_TIMESTAMP(3)");
    }

    @Test void outboxEventBackedOffAndPolledInSameMillisecondIsDue() throws Exception {
        PinnedClock clock = new PinnedClock(T0);
        WorkOrderOutboxRelay relay = new WorkOrderOutboxRelay(jdbc, tx, sender, clock, 10);
        String id = UUID.randomUUID().toString();
        jdbc.update("insert into outbox_event (event_id, aggregate_id, event_type, payload, occurred_at, topic, tag) values (?, 'WO-1', 'DefectReported', '{}', ?, 'mes-workorder-quality', 'DefectReported')",
            id, DbTimes.ts(T0));
        // 第一次投递失败：next_attempt_at = T0 + 1s（带亚毫秒）
        doThrow(new IllegalStateException("broker 不可用")).doNothing().when(sender).send(any(), any(), any(), any(), any());
        assertThat(relay.publishBatch()).isZero();

        // 退避到期的同一毫秒内拉批：必须判为到期并投递成功
        clock.pin(T0.plus(WorkOrderOutboxRelay.backoff(1)));
        assertThat(relay.publishBatch()).as("同一毫秒内写入与拉取，事件应已到期").isEqualTo(1);
        assertThat(jdbc.queryForObject("select status from outbox_event where event_id = ?", String.class, id)).isEqualTo("SENT");
    }

    @Test void releaseTaskBackedOffAndPolledInSameMillisecondIsDue() {
        PinnedClock clock = new PinnedClock(T0);
        ReservationReleaseWorker worker = new ReservationReleaseWorker(jdbc, tx, materials, metrics, clock, 10, Duration.ofSeconds(60));
        String taskId = UUID.randomUUID().toString();
        jdbc.update("insert into wo_reservation_release_task (task_id, order_id, reservation_id, reason, idempotency_key, status, attempts, created_at) values (?, 'WO-1', 'R-1', 'WORK_ORDER_CANCELLED', 'cancel-WO-1-R-1', 'PENDING', 0, ?)",
            taskId, DbTimes.ts(T0));
        // 第一次释放失败：按退避写 next_attempt_at = T0 + 1s（带亚毫秒）
        when(materials.release(anyString(), anyString(), any())).thenThrow(new RuntimeException("库存暂不可用")).thenReturn("RELEASED");
        assertThat(worker.runBatch()).isZero();
        Timestamp next = jdbc.queryForObject("select next_attempt_at from wo_reservation_release_task where task_id = ?", Timestamp.class, taskId);
        assertThat(next.toInstant()).as("写入前已截断到毫秒").isEqualTo(Instant.parse("2026-01-01T00:00:01.000Z"));

        // 退避到期的同一毫秒内拉批：必须领取并完成
        clock.pin(T0.plus(WorkOrderOutboxRelay.backoff(1)));
        assertThat(worker.runBatch()).as("同一毫秒内写入与拉取，任务应已到期").isEqualTo(1);
        assertThat(jdbc.queryForObject("select status from wo_reservation_release_task where task_id = ?", String.class, taskId)).isEqualTo("DONE");
    }
}
