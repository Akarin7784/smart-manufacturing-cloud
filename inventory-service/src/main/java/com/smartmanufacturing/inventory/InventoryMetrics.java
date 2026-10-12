package com.smartmanufacturing.inventory;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.DistributionSummary;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import java.math.BigDecimal;
import java.util.function.Supplier;

/**
 * 库存业务指标（Prometheus 名称见各方法）。标签只使用低基数取值（结果、状态、流水类型、计量单位），
 * <b>不包含工单号、物料号、预留单号</b>，避免时间序列数量随业务数据无限增长。
 * <p>
 * 状态迁移与出入库数量在事务<b>提交后</b>才计数：回滚（如 409 整单失败、死锁重试）不会产生虚假计数。
 */
@Component
public class InventoryMetrics {
    private final MeterRegistry registry;
    private final Timer lockWait;

    public InventoryMetrics(MeterRegistry registry, JdbcTemplate jdbc) {
        this.registry = registry;
        // mes_inventory_stock_lock_wait_seconds：获取现存量行锁（SELECT ... FOR UPDATE）的等待时间，用于发现热点物料争用。
        this.lockWait = Timer.builder("mes.inventory.stock.lock.wait").description("现存量行锁等待时间").register(registry);
        // mes_inventory_outbox_pending：待投递事件数，抓取时查询（走 status, next_attempt_at 索引）；持续增长说明 MQ 不可用。
        Gauge.builder("mes.inventory.outbox.pending", jdbc, j -> {
                Long n = j.queryForObject("select count(*) from outbox_event where status = 'NEW'", Long.class);
                return n == null ? 0 : n;
            }).description("outbox 待投递事件数").register(registry);
    }

    /**
     * mes_inventory_reservation_total{result}：预留创建结果。取值为 ok、replay、8 种 ProblemType 的下划线写法
     * （insufficient_stock、over_consume、idempotency_key_conflict、reservation_expired、reservation_released、
     * reservation_consumed、line_closed、validation_error）以及兜底 error。
     * 请求体 Bean Validation 失败发生在进入控制器之前，不计入本指标。
     */
    public void reservation(String result) {
        Counter.builder("mes.inventory.reservation").description("预留创建请求结果").tag("result", result).register(registry).increment();
    }

    /** mes_inventory_reservation_transition_total{to}：预留单进入某状态的次数（提交后计数）。 */
    public void transition(ReservationDtos.Status to) {
        afterCommit(() -> Counter.builder("mes.inventory.reservation.transition").description("预留单状态迁移")
            .tag("to", to.name()).register(registry).increment());
    }

    /** mes_inventory_movement_qty{kind=inbound|outbound|consume,uom}：出入库与扣减数量（绝对值，提交后计数）。 */
    public void movement(String txnType, String uom, BigDecimal qty) {
        double amount = qty.abs().doubleValue();
        String kind = txnType.toLowerCase(java.util.Locale.ROOT);
        afterCommit(() -> DistributionSummary.builder("mes.inventory.movement.qty").description("出入库数量")
            .tag("kind", kind).tag("uom", uom).register(registry).record(amount));
    }

    /** 记录获取行锁的耗时。 */
    public <T> T timeLock(Supplier<T> lock) { return lockWait.record(lock); }

    /** 有事务时注册提交后回调；无事务（不应出现）时直接执行。 */
    private static void afterCommit(Runnable r) {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override public void afterCommit() { r.run(); }
            });
        } else {
            r.run();
        }
    }
}
