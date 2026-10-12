package com.smartmanufacturing.workorder;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 幂等记录清理的真实 MySQL 8.4 集成测试：用 Flyway 执行生产迁移脚本建表（不加载 db/demo），
 * 验证“先按 created_at 升序选主键、再按主键删除”的 SQL 在 MySQL 上可执行，并且优先删除最旧的记录、不越过截止点。
 * 不启动 Spring 上下文，避免依赖设备服务等外部组件；Docker 不可用时直接失败（与 #8 的约定一致，Windows CI 用 -DskipITs）。
 */
@Testcontainers
class IdempotencyRecordCleanerMySqlIT {
    @Container
    static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.4");
    private static final Instant NOW = Instant.parse("2026-10-12T00:00:00Z");
    private static JdbcTemplate jdbc;

    /** 只执行 db/migration，与 test profile 一致。 */
    @BeforeAll
    static void migrate() {
        DriverManagerDataSource ds = new DriverManagerDataSource(MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword());
        Flyway.configure().dataSource(ds).locations("classpath:db/migration").load().migrate();
        jdbc = new JdbcTemplate(ds);
    }

    @BeforeEach
    void clean() { jdbc.update("delete from work_order_request"); }

    private void record(String key, Instant createdAt) {
        jdbc.update("insert into work_order_request (principal, idempotency_key, request_hash, order_id, created_at) values (?,?,?,?,?)",
            "alice", key, "0".repeat(64), "order-" + key, Timestamp.from(createdAt));
    }

    /** 批次上限为 1 时，单批只删除最旧的那条，证明按 created_at 顺序删除且不触碰未过期记录。 */
    @Test @DisplayName("MySQL 上按时间顺序分批删除过期记录")
    void deletesOldestFirstOnMySql() {
        record("oldest", NOW.minus(Duration.ofDays(30)));
        record("older", NOW.minus(Duration.ofDays(10)));
        record("fresh", NOW.minus(Duration.ofDays(1)));
        IdempotencyRecordCleaner cleaner = new IdempotencyRecordCleaner(jdbc, Clock.fixed(NOW, ZoneOffset.UTC), Duration.ofDays(7), 1, 1);

        assertThat(cleaner.purgeExpired()).isEqualTo(1);
        assertThat(jdbc.queryForList("select idempotency_key from work_order_request order by created_at", String.class))
            .containsExactly("older", "fresh");
        // 再运行一次删掉剩余过期记录，未过期的保留。
        assertThat(cleaner.purgeExpired()).isEqualTo(1);
        assertThat(jdbc.queryForList("select idempotency_key from work_order_request", String.class)).containsExactly("fresh");
    }
}
