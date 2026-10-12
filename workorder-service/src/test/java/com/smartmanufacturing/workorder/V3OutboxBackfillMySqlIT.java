package com.smartmanufacturing.workorder;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import java.util.Map;
import static org.assertj.core.api.Assertions.*;

/**
 * V3 对 V2 遗留 outbox 行的回填（真实 MySQL 8.4，直接驱动 Flyway，不启动 Spring）：
 * 生命周期事件补齐 topic / tag 后保持 NEW 待投递；无法映射的事件类型标记 DISCARDED 并写明原因；已发布行标记 SENT。
 */
@Testcontainers
class V3OutboxBackfillMySqlIT {
    @Container
    static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.4");

    @Test void legacyRowsAreRoutedOrDiscarded() {
        var ds = new DriverManagerDataSource(MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword());
        Flyway.configure().dataSource(ds).locations("classpath:db/migration").target("2").load().migrate();
        JdbcTemplate jdbc = new JdbcTemplate(ds);
        String insert = "insert into outbox_event (event_id, aggregate_id, event_type, payload, occurred_at, published_at) values (?, 'WO-1', ?, '{}', now(), ?)";
        jdbc.update(insert, "e-released", "WorkOrderReleased", null);
        jdbc.update(insert, "e-held", "WorkOrderHeld", null);
        jdbc.update(insert, "e-resumed", "WorkOrderResumed", null);
        jdbc.update(insert, "e-cleared", "WorkOrderHoldReasonCleared", null);
        jdbc.update(insert, "e-unknown", "SomethingLegacy", null);
        jdbc.update(insert, "e-published", "WorkOrderReleased", new java.sql.Timestamp(System.currentTimeMillis()));

        Flyway.configure().dataSource(ds).locations("classpath:db/migration").load().migrate();

        for (String id : new String[] {"e-released", "e-held", "e-resumed", "e-cleared"}) {
            Map<String, Object> r = jdbc.queryForMap("select topic, tag, event_type, status, discard_reason from outbox_event where event_id = ?", id);
            assertThat(r.get("topic")).as(id).isEqualTo("mes-workorder-event");
            assertThat(r.get("tag")).as(id).isEqualTo(r.get("event_type"));
            assertThat(r.get("status")).as(id).isEqualTo("NEW");
            assertThat(r.get("discard_reason")).as(id).isNull();
        }
        Map<String, Object> unknown = jdbc.queryForMap("select topic, status, discard_reason from outbox_event where event_id = 'e-unknown'");
        assertThat(unknown.get("topic")).isNull();
        assertThat(unknown.get("status")).isEqualTo("DISCARDED");
        assertThat(unknown.get("discard_reason")).isEqualTo("V3_UNMAPPED_EVENT_TYPE");
        assertThat(jdbc.queryForObject("select status from outbox_event where event_id = 'e-published'", String.class)).isEqualTo("SENT");
        // 待投递数（Gauge 口径）不含 DISCARDED / SENT
        assertThat(jdbc.queryForObject("select count(*) from outbox_event where status = 'NEW'", Integer.class)).isEqualTo(4);
    }
}
