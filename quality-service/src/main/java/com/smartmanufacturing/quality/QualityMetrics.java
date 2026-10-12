package com.smartmanufacturing.quality;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import java.math.BigDecimal;

/**
 * 质检业务指标（名称与标签见 docs/observability.md 第 5 节质检部分）。标签只使用低基数取值（结果、状态、处置类型、单位、tag），
 * <b>不包含 NCR 号、工单号、报工 ID</b>，避免时间序列随业务数据无限增长。
 * <p>
 * 状态迁移、处置数量、消费结果、暂存过期均在事务<b>提交后</b>计数：回滚（如重复投递、死锁重试）不会产生虚假计数。
 */
@Component
public class QualityMetrics {
    /** 新建 NCR 时迁移指标的 from 取值。 */
    public static final String FROM_NONE = "NONE";
    /** 已处置 NCR 被冲销的消费结果取值，与告警规则及 observability.md 第 5 节一致，不得改名。 */
    public static final String DISPOSED_REVERSAL_CONFLICT = "disposed_reversal_conflict";
    /** 不可恢复坏消息（已留档并 ACK）的消费结果取值（#30），告警 QualityInvalidEvent 依赖，不得改名。 */
    public static final String INVALID = "invalid";
    /** 清理任务涉及的表，作为 mes_quality_cleanup_deleted_total 的 table 标签取值。 */
    public static final java.util.List<String> CLEANUP_TABLES = java.util.List.of("consumed_event", "defect_report_lock");
    private final MeterRegistry registry;
    private final Counter pendingExpired;

    public QualityMetrics(MeterRegistry registry, JdbcTemplate jdbc, MqProperties mq) {
        this.registry = registry;
        // 告警 QualityDisposedReversalConflict 依赖的序列启动即注册为 0：
        // mes_quality_event_consumed_total{tag="DefectReversed",result="disposed_reversal_conflict"}，
        // 避免首次冲突前序列不存在导致 increase()/rate() 无数据、告警无法从 0 跳变。
        Counter.builder("mes.quality.event.consumed").description("不良品事件消费结果")
            .tag("tag", mq.defectReversedTag()).tag("result", DISPOSED_REVERSAL_CONFLICT).register(registry);
        // 坏消息计数（#30）：两种 tag 都预注册为 0，告警按 result="invalid" 求和。
        for (String tag : java.util.List.of(mq.defectReportedTag(), mq.defectReversedTag())) {
            Counter.builder("mes.quality.event.consumed").description("不良品事件消费结果")
                .tag("tag", tag).tag("result", INVALID).register(registry);
        }
        // mes_quality_cleanup_deleted_total{table}（#31）：去重表与报工锁表清理删除行数，启动预注册为 0。
        for (String table : CLEANUP_TABLES) cleanupCounter(table);
        // mes_quality_pending_reversal_expired_total：暂存冲销超过 TTL 未对冲的次数，启动即注册为 0，告警 QualityReversalOrphan 据此判断。
        this.pendingExpired = Counter.builder("mes.quality.pending.reversal.expired").description("暂存冲销超时未对冲次数").register(registry);
        // mes_quality_outbox_pending：待投递事件数，抓取时查询（走 status, next_attempt_at 索引）；持续增长说明 MQ 不可用。
        Gauge.builder("mes.quality.outbox.pending", jdbc, j -> count(j, "select count(*) from outbox_event where status = 'NEW'"))
            .description("outbox 待投递事件数").register(registry);
        // mes_quality_ncr_open：未关闭（OPEN/UNDER_REVIEW/DISPOSED）的 NCR 数，抓取时查询（走 status 索引）。
        Gauge.builder("mes.quality.ncr.open", jdbc,
                j -> count(j, "select count(*) from ncr where status in ('OPEN', 'UNDER_REVIEW', 'DISPOSED')"))
            .description("未关闭的 NCR 数").register(registry);
    }

    private static double count(JdbcTemplate jdbc, String sql) {
        Long n = jdbc.queryForObject(sql, Long.class);
        return n == null ? 0 : n;
    }

    /**
     * mes_quality_api_total{result}：修改类接口结果。取值为 ok、replay、7 种 ProblemType 的下划线写法以及兜底 error。
     * 在事务结束后由控制器调用，因此不需要 afterCommit。
     */
    public void api(String result) {
        Counter.builder("mes.quality.api").description("质检修改类接口结果").tag("result", result).register(registry).increment();
    }

    /** mes_quality_ncr_transition_total{from,to}：NCR 状态迁移（提交后计数），新建 NCR 的 from 为 NONE。 */
    public void transition(String from, String to) {
        afterCommit(() -> Counter.builder("mes.quality.ncr.transition").description("NCR 状态迁移")
            .tag("from", from).tag("to", to).register(registry).increment());
    }

    /** mes_quality_disposition_qty_total{type,uom}：处置数量累计（提交后计数），不同 uom 不能相加。 */
    public void disposition(String type, String uom, BigDecimal qty) {
        double amount = qty.doubleValue();
        afterCommit(() -> Counter.builder("mes.quality.disposition.qty").description("处置数量")
            .tag("type", type).tag("uom", uom).register(registry).increment(amount));
    }

    /**
     * mes_quality_event_consumed_total{tag,result}：消费 mes-workorder-quality 的结果，
     * result 取 ok / duplicate / pending / matched / disposed_reversal_conflict / invalid / error。
     * 由消费入口在事务结束后调用（提交成功才计 ok 等结果，回滚计 duplicate 或 error）。
     */
    public void consumed(String tag, String result) {
        Counter.builder("mes.quality.event.consumed").description("不良品事件消费结果")
            .tag("tag", tag).tag("result", result).register(registry).increment();
    }

    /** 清理任务删除 n 行（每批事务提交后由清理任务调用）。 */
    public void cleanupDeleted(String table, int n) {
        if (n > 0) cleanupCounter(table).increment(n);
    }

    private Counter cleanupCounter(String table) {
        return Counter.builder("mes.quality.cleanup.deleted").description("去重与报工锁表清理删除行数").tag("table", table).register(registry);
    }

    /** 暂存冲销过期 n 条（提交后计数）。 */
    public void pendingExpired(int n) {
        if (n > 0) afterCommit(() -> pendingExpired.increment(n));
    }

    /** 有事务时注册提交后回调；无事务时直接执行。 */
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
