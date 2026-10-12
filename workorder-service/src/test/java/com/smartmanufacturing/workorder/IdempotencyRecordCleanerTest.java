package com.smartmanufacturing.workorder;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 幂等记录清理单元测试：使用 H2（MySQL 模式）执行真实的 V1 迁移脚本建表，并注入固定时钟，
 * 保护的行为是：只删除早于 7 天保留期的记录、按批次删除直至清空、批次上限生效、不触碰工单表。
 */
class IdempotencyRecordCleanerTest {
    /** 固定“当前时间”，使截止点可预测。 */
    private static final Instant NOW = Instant.parse("2026-10-12T00:00:00Z");
    private SingleConnectionDataSource dataSource;
    private JdbcTemplate jdbc;

    @BeforeEach
    void setUp() {
        dataSource = new SingleConnectionDataSource("jdbc:h2:mem:cleaner-" + UUID.randomUUID() + ";MODE=MySQL", true);
        // 直接执行 Flyway 的 V1 脚本，保证测试表结构（含 created_at 索引）与迁移一致。
        new ResourceDatabasePopulator(new ClassPathResource("db/migration/V1__init.sql")).execute(dataSource);
        jdbc = new JdbcTemplate(dataSource);
    }

    @AfterEach
    void tearDown() { dataSource.destroy(); }

    /** 插入一条幂等记录，createdAt 由测试指定。 */
    private void record(String key, Instant createdAt) {
        jdbc.update("insert into work_order_request (principal, idempotency_key, request_hash, order_id, created_at) values (?,?,?,?,?)",
            "alice", key, "0".repeat(64), "order-" + key, Timestamp.from(createdAt));
    }

    private IdempotencyRecordCleaner cleaner(int batchSize, int maxBatches) {
        return new IdempotencyRecordCleaner(jdbc, Clock.fixed(NOW, ZoneOffset.UTC), Duration.ofDays(7), batchSize, maxBatches);
    }

    private int remaining() { return jdbc.queryForObject("select count(*) from work_order_request", Integer.class); }

    /** 早于截止点的删除；恰好处于截止点及之后的保留（严格小于），工单表不受影响。 */
    @Test @DisplayName("只删除超过 7 天的记录")
    void deletesOnlyExpired() {
        jdbc.update("insert into work_order (id, device_id, product, quantity, status) values ('order-old','D001','gear',1,'CREATED')");
        record("old", NOW.minus(Duration.ofDays(8)));
        record("edge", NOW.minus(Duration.ofDays(7)));
        record("fresh", NOW.minus(Duration.ofHours(1)));

        assertThat(cleaner(1000, 10).purgeExpired()).isEqualTo(1);
        assertThat(jdbc.queryForList("select idempotency_key from work_order_request order by idempotency_key", String.class))
            .containsExactly("edge", "fresh");
        // 只清理幂等记录，工单本身必须保留。
        assertThat(jdbc.queryForObject("select count(*) from work_order", Integer.class)).isEqualTo(1);
    }

    /** 过期记录多于一批时循环删除直至清空：5 条、每批 2 条，共 3 批。 */
    @Test @DisplayName("分批删除直至清空")
    void deletesInBatches() {
        for (int i = 0; i < 5; i++) record("k" + i, NOW.minus(Duration.ofDays(30)));
        assertThat(cleaner(2, 10).purgeExpired()).isEqualTo(5);
        assertThat(remaining()).isZero();
    }

    /** 单次运行受最大批数限制，剩余记录留给下次运行，防止任务长时间占用连接。 */
    @Test @DisplayName("单次运行不超过最大批数")
    void respectsMaxBatches() {
        for (int i = 0; i < 5; i++) record("k" + i, NOW.minus(Duration.ofDays(30)));
        assertThat(cleaner(2, 1).purgeExpired()).isEqualTo(2);
        assertThat(remaining()).isEqualTo(3);
    }

    /** 非法参数在启动时即失败，避免批次为 0 导致死循环或保留期为 0 误删全部记录。 */
    @Test @DisplayName("非法参数拒绝启动")
    void rejectsInvalidSettings() {
        Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
        assertThatThrownBy(() -> new IdempotencyRecordCleaner(jdbc, clock, Duration.ZERO, 10, 1)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new IdempotencyRecordCleaner(jdbc, clock, Duration.ofDays(7), 0, 1)).isInstanceOf(IllegalArgumentException.class);
    }
}
