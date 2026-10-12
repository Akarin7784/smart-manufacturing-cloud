package com.smartmanufacturing.workorder;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import java.util.List;

/**
 * 库存客户端相关指标（名称与标签见 docs/observability.md 第 5 节）。所有 result 取值启动时以 0 预注册，
 * 保证 increase() 能计到第一次增量（告警依赖）。不使用工单号、预留单号等高基数标签。
 */
@Component
class InventoryClientMetrics {
    /** 取消释放任务的结果取值。 */
    static final List<String> RELEASE_RESULTS = List.of("released", "expired", "reservation_gone", "reservation_consumed", "reservation_not_found",
        "auth_error", "unavailable", "contract_mismatch", "error");
    /** 库存调用的操作名（与 auth_failure 的 operation 一致，token 除外）。 */
    static final List<String> CALL_OPERATIONS = List.of("reserve", "renew", "release", "get");
    /** 预留事件的处理结果取值。 */
    static final List<String> EVENT_RESULTS = List.of("applied", "duplicate", "stale", "unknown_reservation", "invalid");
    private final MeterRegistry registry;

    InventoryClientMetrics(MeterRegistry registry, JdbcTemplate jdbc) {
        this.registry = registry;
        RELEASE_RESULTS.forEach(r -> release(r));
        EVENT_RESULTS.forEach(r -> event(r));
        for (String op : List.of("reserve", "renew", "release", "get", "token")) auth(op);
        CALL_OPERATIONS.forEach(op -> contractMismatch(op));
        // mes_workorder_reservation_release_pending：待执行（PENDING）的取消释放任务数，每次抓取时查询
        Gauge.builder("mes.workorder.reservation.release.pending", () -> {
                Long n = jdbc.queryForObject("select count(*) from wo_reservation_release_task where status = 'PENDING'", Long.class);
                return n == null ? 0 : n;
            }).description("待执行的预留释放任务数").register(registry);
    }

    /** mes_workorder_reservation_release_total{result}：取消释放任务每次尝试的结果。 */
    private Counter release(String result) {
        return Counter.builder("mes.workorder.reservation.release").tag("result", result).description("预留释放任务结果").register(registry);
    }
    /** mes_workorder_reservation_event_total{result}：库存预留事件处理结果。 */
    private Counter event(String result) {
        return Counter.builder("mes.workorder.reservation.event").tag("result", result).description("库存预留事件处理结果").register(registry);
    }
    /** mes_workorder_inventory_auth_failure_total{operation}：库存拒绝服务令牌（401/403）的次数，需告警。 */
    private Counter auth(String operation) {
        return Counter.builder("mes.workorder.inventory.auth.failure").tag("operation", operation).description("库存拒绝服务令牌次数").register(registry);
    }

    /**
     * mes_workorder_inventory_call_failure_total{operation, result="contract_mismatch"}：库存返回不符合契约
     * （422 uom-mismatch、本服务不应收到的 Problem type、未约定的状态码、响应体缺失或结构无法解析）。
     * 单独计数，不并入网络 / 5xx 错误，供告警 WorkorderReservationContractMismatch 使用。
     */
    private Counter contractMismatch(String operation) {
        return Counter.builder("mes.workorder.inventory.call.failure").tag("operation", operation).tag("result", "contract_mismatch")
            .description("库存调用失败（按原因）").register(registry);
    }

    void releaseResult(String result) { release(result).increment(); }
    void eventResult(String result) { event(result).increment(); }
    void authFailure(String operation) { auth(operation).increment(); }
    void contractMismatchObserved(String operation) { contractMismatch(operation).increment(); }
}
