package com.smartmanufacturing.quality;

import com.smartmanufacturing.quality.InventoryQcHoldClient.Action;
import com.smartmanufacturing.quality.InventoryQcHoldClient.Booked;
import com.smartmanufacturing.quality.InventoryQcHoldClient.Call;
import com.smartmanufacturing.quality.InventoryQcHoldClient.Outcome;
import com.smartmanufacturing.quality.InventoryQcHoldClient.Rejected;
import com.smartmanufacturing.quality.InventoryQcHoldClient.Retryable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 库存回写执行器（设计 §5），由 {@link QualitySchedules} 每 2s 触发一次 {@link #runBatch()}；测试中直接调用。
 * <ol>
 *   <li><b>领取</b>（短事务）：取到期的 PENDING 任务，以及租约已到期的 RUNNING 任务（进程在调用中途崩溃留下的），
 *       {@code FOR UPDATE SKIP LOCKED}，每批 50；同一 NCR 中 id 更小的任务没有 PENDING / RUNNING / FAILED（<b>同一 NCR 按 id 串行</b>，
 *       保证先入后出、VOID 排在 HOLD 之后）。领取即置 RUNNING、attempts+1、记录 first_attempted_at，next_attempt_at 为租约到期时间。</li>
 *   <li><b>调用</b>：在事务外调用库存（不持有数据库锁等待网络）。</li>
 *   <li><b>回写</b>（短事务）：按 id + attempts 栅栏更新结果，租约过期后被别的实例重新领取的旧结果直接丢弃。
 *       成功 → DONE；可重试 → 回到 PENDING 指数退避（2s 起、上限 5min），超过 20 次 → FAILED（UNCERTAIN）；不可重试 → FAILED，
 *       HOLD 被拒绝且确定未记账时取消其后的 VOID；鉴权失败与契约不一致另计专用指标。</li>
 * </ol>
 * 每次执行结果计入 mes_quality_writeback_total{result}（事务提交后计数）。
 */
@Component
public class WritebackWorker {
    private static final Logger log = LoggerFactory.getLogger(WritebackWorker.class);
    private static final String CLAIM = "select w.id, w.ncr_no, w.action, w.reason, w.disposition_id, w.idempotency_key, w.material_code,"
        + " w.lot_no, w.uom, w.qty, w.attempts from inventory_writeback w"
        + " where w.status in ('PENDING', 'RUNNING') and w.next_attempt_at <= ?"
        + " and not exists (select 1 from inventory_writeback p where p.ncr_no = w.ncr_no and p.id < w.id"
        + " and p.status in ('PENDING', 'RUNNING', 'FAILED'))"
        + " order by w.next_attempt_at, w.id limit ? for update skip locked";

    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;
    private final InventoryQcHoldClient client;
    private final WritebackService writebacks;
    private final QualityMetrics metrics;
    private final WritebackProperties props;
    private final Clock clock;
    /** 未配置密钥时只告警一次，避免每 2s 刷屏。 */
    private final AtomicBoolean warnedNotReady = new AtomicBoolean();

    public WritebackWorker(JdbcTemplate jdbc, TransactionTemplate tx, InventoryQcHoldClient client, WritebackService writebacks,
                           QualityMetrics metrics, WritebackProperties props, Clock clock) {
        this.jdbc = jdbc; this.tx = tx; this.client = client; this.writebacks = writebacks; this.metrics = metrics;
        this.props = props; this.clock = clock;
    }

    /** 一个已领取的任务。claimedAttempts 为领取后的 attempts，用作回写栅栏。 */
    private record Claimed(long id, String ncrNo, Call call, int claimedAttempts) {}

    /**
     * 执行一批到期任务。
     * @return 本批执行的任务数
     */
    public int runBatch() {
        if (!client.ready()) {
            if (warnedNotReady.compareAndSet(false, true))
                log.warn("未配置 QUALITY_CLIENT_SECRET，库存回写任务暂不执行（保持 PENDING，见 mes_quality_writeback_pending）");
            return 0;
        }
        List<Claimed> claimed = tx.execute(status -> claim());
        if (claimed == null) return 0;
        for (Claimed c : claimed) {
            Outcome outcome = client.call(c.call());
            tx.executeWithoutResult(status -> apply(c, outcome));
        }
        return claimed.size();
    }

    private List<Claimed> claim() {
        Instant now = clock.instant().truncatedTo(ChronoUnit.MILLIS);
        List<Claimed> rows = jdbc.query(CLAIM, (rs, i) -> {
            long disp = rs.getLong(5);
            Long dispositionId = rs.wasNull() ? null : disp;
            Call call = new Call(Action.valueOf(rs.getString(3)), rs.getString(6), rs.getString(2), rs.getString(7), rs.getString(9),
                rs.getString(8), Quantities.format(rs.getBigDecimal(10)), rs.getString(4), dispositionId);
            return new Claimed(rs.getLong(1), rs.getString(2), call, rs.getInt(11) + 1);
        }, Timestamp.from(now), props.batchSize());
        Timestamp ts = Timestamp.from(now);
        Timestamp leaseUntil = Timestamp.from(now.plus(props.lease()));
        for (Claimed c : rows)
            jdbc.update("update inventory_writeback set status = 'RUNNING', attempts = ?, first_attempted_at = coalesce(first_attempted_at, ?), next_attempt_at = ?,"
                + " updated_at = ? where id = ?", c.claimedAttempts(), ts, leaseUntil, ts, c.id());
        return rows;
    }

    private void apply(Claimed c, Outcome outcome) {
        Timestamp now = Timestamp.from(clock.instant().truncatedTo(ChronoUnit.MILLIS));
        // 栅栏：只有仍是本次领取（状态 RUNNING 且 attempts 未变）才回写，防止租约过期后的旧结果覆盖新一轮执行。
        List<int[]> current = jdbc.query("select attempts, unknown_404_count from inventory_writeback where id = ? and status = 'RUNNING' for update",
            (rs, i) -> new int[] {rs.getInt(1), rs.getInt(2)}, c.id());
        if (current.isEmpty() || current.get(0)[0] != c.claimedAttempts()) {
            log.warn("回写任务 {} 的执行结果已过期（租约到期后被重新领取或状态已变），丢弃本次结果", c.id());
            return;
        }
        switch (outcome) {
            case Booked b -> {
                jdbc.update("update inventory_writeback set status = 'DONE', next_attempt_at = null, failure_kind = null, last_error = null,"
                    + " inventory_txn_id = ?, updated_at = ? where id = ?", b.txnId(), now, c.id());
                metrics.writeback(b.replayed() ? QualityMetrics.WRITEBACK_REPLAY : QualityMetrics.WRITEBACK_OK);
                if (b.contractMismatch()) metrics.inventoryContractMismatch(InventoryQcHoldClient.operation(c.call().action()));
            }
            case Retryable r -> {
                String op = InventoryQcHoldClient.operation(c.call().action());
                if (r.reason() == InventoryQcHoldClient.RetryReason.NOT_FOUND_UNKNOWN) {
                    int n = current.get(0)[1] + 1;
                    jdbc.update("update inventory_writeback set unknown_404_count = ? where id = ?", n, c.id());
                    if (n > props.maxUnknown404Retries()) {
                        // 约定 type 以外的 404 超过上限：多为路由或部署问题，无法判断库存是否记账，置 FAILED（不确定），计契约不一致。
                        fail(c, WritebackService.UNCERTAIN, "未约定的 404 已重试 " + props.maxUnknown404Retries() + " 次：" + r.error(), now);
                        metrics.inventoryContractMismatch(op);
                        return;
                    }
                }
                if (c.claimedAttempts() >= props.maxAttempts()) {
                    fail(c, WritebackService.UNCERTAIN, "重试 " + c.claimedAttempts() + " 次仍失败：" + r.error(), now);
                } else {
                    Timestamp next = Timestamp.from(now.toInstant().plus(backoff(c.claimedAttempts())));
                    jdbc.update("update inventory_writeback set status = 'PENDING', next_attempt_at = ?, last_error = ?, updated_at = ? where id = ?",
                        next, HttpInventoryQcHoldClient.truncate(r.error()), now, c.id());
                    metrics.writeback(QualityMetrics.WRITEBACK_RETRY);
                    metrics.writebackRetry(op, r.reason().label());
                }
            }
            case Rejected r -> {
                fail(c, r.certain() ? WritebackService.REJECTED : WritebackService.UNCERTAIN, r.error(), now);
                // HOLD 被库存明确拒绝：库存确定未记账，排在其后的作废扣回无需执行（设计 §4）。结果不确定时 VOID 保持 PENDING。
                if (r.certain() && c.call().action() == Action.HOLD) writebacks.cancelPendingVoid(c.ncrNo());
                switch (r.signal()) {
                    case AUTH -> metrics.inventoryAuthFailure(InventoryQcHoldClient.operation(c.call().action()));
                    case TOKEN_AUTH -> metrics.inventoryAuthFailure("token");
                    case CONTRACT -> metrics.inventoryContractMismatch(InventoryQcHoldClient.operation(c.call().action()));
                    case NONE -> { }
                }
            }
        }
    }

    private void fail(Claimed c, String kind, String error, Timestamp now) {
        jdbc.update("update inventory_writeback set status = 'FAILED', next_attempt_at = null, failure_kind = ?, last_error = ?, updated_at = ?"
            + " where id = ?", kind, HttpInventoryQcHoldClient.truncate(error), now, c.id());
        metrics.writeback(QualityMetrics.WRITEBACK_FAILED);
        log.warn("库存回写任务 {}（{} {}）失败（{}）：{}", c.id(), c.call().action(), c.call().idempotencyKey(), kind, error);
    }

    /** 第 n 次失败后的退避：initial × 2^(n-1)，上限 maxBackoff。 */
    Duration backoff(int attempts) {
        Duration d = props.initialBackoff();
        for (int i = 1; i < attempts && d.compareTo(props.maxBackoff()) < 0; i++) d = d.multipliedBy(2);
        return d.compareTo(props.maxBackoff()) > 0 ? props.maxBackoff() : d;
    }
}
