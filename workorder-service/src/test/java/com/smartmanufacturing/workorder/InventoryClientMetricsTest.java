package com.smartmanufacturing.workorder;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** 启动即以 0 预注册全部标签值（告警的 increase() 依赖序列预先存在），并注册待释放任务 Gauge。 */
class InventoryClientMetricsTest {
    @Test void allLabelValuesArePreRegisteredAtZero() {
        var registry = new SimpleMeterRegistry();
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        when(jdbc.queryForObject(anyString(), eq(Long.class))).thenReturn(0L);
        new InventoryClientMetrics(registry, jdbc);
        for (String r : java.util.List.of("released", "expired", "reservation_gone", "reservation_consumed", "reservation_not_found", "auth_error", "unavailable", "contract_mismatch", "error"))
            assertThat(registry.find("mes.workorder.reservation.release").tag("result", r).counter()).as(r).isNotNull()
                .satisfies(c -> assertThat(c.count()).isZero());
        for (String r : java.util.List.of("applied", "duplicate", "stale", "unknown_reservation", "invalid"))
            assertThat(registry.find("mes.workorder.reservation.event").tag("result", r).counter()).as(r).isNotNull()
                .satisfies(c -> assertThat(c.count()).isZero());
        for (String op : java.util.List.of("reserve", "renew", "release", "get", "token"))
            assertThat(registry.find("mes.workorder.inventory.auth.failure").tag("operation", op).counter()).as(op).isNotNull()
                .satisfies(c -> assertThat(c.count()).isZero());
        assertThat(registry.find("mes.workorder.reservation.release.pending").gauge()).isNotNull().satisfies(g -> assertThat(g.value()).isZero());
    }

    /** 释放 200 + RELEASED / EXPIRED 计为 released / expired（成功），不会落到失败标签。 */
    @Test void successStatusesMapToSuccessLabels() {
        var registry = new SimpleMeterRegistry();
        var m = new InventoryClientMetrics(registry, mock(JdbcTemplate.class));
        m.releaseResult("RELEASED".toLowerCase()); m.releaseResult("EXPIRED".toLowerCase());
        assertThat(registry.find("mes.workorder.reservation.release").tag("result", "released").counter().count()).isEqualTo(1);
        assertThat(registry.find("mes.workorder.reservation.release").tag("result", "expired").counter().count()).isEqualTo(1);
        assertThat(registry.find("mes.workorder.reservation.release").tag("result", "error").counter().count()).isZero();
    }
}
