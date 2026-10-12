package com.smartmanufacturing.workorder;
import io.micrometer.core.instrument.*;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import java.math.BigDecimal;
import java.util.function.Supplier;

/**
 * 报工相关 Micrometer 指标（Prometheus 名称见各方法）。禁止使用工单号等高基数标签。
 * 业务计数只在事务提交后、且非幂等重放时累加，回滚或重放不计入。
 */
@Component
class ReportingMetrics {
    private final MeterRegistry registry;
    private final Timer lockWait;
    private final Counter reversals;

    ReportingMetrics(MeterRegistry registry, org.springframework.jdbc.core.JdbcTemplate jdbc) {
        this.registry = registry;
        // mes_workorder_report_lock_wait_seconds：等待工单行 SELECT … FOR UPDATE 的时间（报工 / 冲销 / 手工完工）
        this.lockWait = Timer.builder("mes.workorder.report.lock.wait").description("等待工单行锁的时间").register(registry);
        // mes_workorder_reversal_total：成功冲销次数
        this.reversals = Counter.builder("mes.workorder.reversal").description("报工冲销次数").register(registry);
        // mes_workorder_outbox_pending：发件箱中 status = NEW 的事件数，每次抓取时查询
        Gauge.builder("mes.workorder.outbox.pending", () -> {
                // 只统计待投递（NEW）；DISCARDED 永不投递，不计入
            Long n = jdbc.queryForObject("select count(*) from outbox_event where status = 'NEW'", Long.class);
                return n == null ? 0 : n;
            }).description("发件箱待投递事件数").register(registry);
        result("ok"); result("replay"); result("error");
        for (WorkOrderProblemType t : WorkOrderProblemType.values()) result(resultOf(t));
    }

    /** mes_workorder_report_qty_total{kind=good|defect|scrap, uom}：报工数量累计（Counter，见 docs/observability.md 第 5 节）。 */
    private Counter qty(String kind, String uom) {
        return Counter.builder("mes.workorder.report.qty").tag("kind", kind).tag("uom", uom).description("报工数量").register(registry);
    }

    /** Problem type 转为结果标签：over-report → over_report。 */
    static String resultOf(WorkOrderProblemType t) { return t.type().replace('-', '_'); }

    /** mes_workorder_report_total{result=ok|replay|<Problem type 下划线形式>|error}：报工请求结果计数。 */
    private Counter result(String result) {
        return Counter.builder("mes.workorder.report").tag("result", result).description("报工请求结果").register(registry);
    }

    void reportOk(BigDecimal g, BigDecimal d, BigDecimal s, String uom) {
        result("ok").increment();
        qty("good", uom).increment(g.doubleValue()); qty("defect", uom).increment(d.doubleValue()); qty("scrap", uom).increment(s.doubleValue());
    }
    void reportReplay() { result("replay").increment(); }
    void reportProblem(WorkOrderProblemType t) { result(t == null ? "error" : resultOf(t)).increment(); }
    void reportError() { result("error").increment(); }
    /** mes_workorder_reversal_qty_total{kind, uom}：被冲销的数量（正数 Counter）；净报工量 = report_qty − reversal_qty。 */
    void reversed(BigDecimal g, BigDecimal d, BigDecimal s, String uom) {
        reversals.increment();
        rev("good", uom).increment(g.abs().doubleValue()); rev("defect", uom).increment(d.abs().doubleValue()); rev("scrap", uom).increment(s.abs().doubleValue());
    }

    private Counter rev(String kind, String uom) {
        return Counter.builder("mes.workorder.reversal.qty").tag("kind", kind).tag("uom", uom).description("冲销数量").register(registry);
    }

    /** mes_workorder_status_transition_total{from,to}：状态迁移计数（下达、冻结、恢复、开工、完工、重开），在当前事务提交后累加。 */
    void transitionAfterCommit(String from, String to) {
        Runnable inc = () -> Counter.builder("mes.workorder.status.transition").tag("from", from).tag("to", to).description("工单状态转换次数").register(registry).increment();
        if (TransactionSynchronizationManager.isSynchronizationActive())
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override public void afterCommit() { inc.run(); }
            });
        else inc.run();
    }

    /** 计时等待行锁的过程。 */
    <T> T timeLock(Supplier<T> lock) { return lockWait.record(lock); }
}
