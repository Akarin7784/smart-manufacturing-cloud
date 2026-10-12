package com.smartmanufacturing.quality;

import com.smartmanufacturing.quality.InventoryQcHoldClient.Action;
import com.smartmanufacturing.quality.NcrDtos.DispositionType;
import com.smartmanufacturing.quality.NcrDtos.WritebackStatus;
import com.smartmanufacturing.quality.NcrDtos.WritebackTask;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;

/**
 * 库存回写任务（inventory_writeback）的写入、作废时机、关闭校验与人工操作（设计见 docs/quality-inventory-writeback-design.md §4、§5）。
 * <ul>
 *   <li>任务写入都要求调用方已开启事务（MANDATORY）：与 NCR 建单、处置行、作废在同一事务提交，业务回滚则任务一并回滚。</li>
 *   <li>幂等键在写入时固定：{@code qc:{ncrNo}:hold}、{@code qc:{ncrNo}:disp:{dispositionId}:scrap|release}、{@code qc:{ncrNo}:void}，
 *       重试与人工重试都不变（库存端按原键去重）。</li>
 *   <li>执行由 {@link WritebackWorker} 在事务外完成；本类只改状态，不调用库存。</li>
 *   <li>时间写入前截断到毫秒（列为 TIMESTAMP(3)，写法与 {@link OutboxWriter} 一致，#46）。</li>
 * </ul>
 */
@Service
public class WritebackService {
    static final String COLUMNS = "id, ncr_no, action, reason, disposition_id, idempotency_key, qty, uom, status, attempts, next_attempt_at,"
        + " last_error, inventory_txn_id, created_at, updated_at";
    /** 放行原因：NCR 作废扣回。 */
    static final String NCR_VOIDED = "NCR_VOIDED";
    /** FAILED 性质：库存明确拒绝，确定未记账。 */
    static final String REJECTED = "REJECTED";
    /** FAILED 性质：结果不确定（超时、5xx 等重试耗尽，或拒绝不能证明未记账）。 */
    static final String UNCERTAIN = "UNCERTAIN";

    private final JdbcTemplate jdbc;
    private final QualityMetrics metrics;
    private final WritebackProperties props;
    private final Clock clock;

    public WritebackService(JdbcTemplate jdbc, QualityMetrics metrics, WritebackProperties props, Clock clock) {
        this.jdbc = jdbc; this.metrics = metrics; this.props = props; this.clock = clock;
    }

    static final RowMapper<WritebackTask> TASK = (rs, i) -> {
        WritebackStatus status = WritebackStatus.valueOf(rs.getString(9));
        Timestamp next = rs.getTimestamp(11);
        long txn = rs.getLong(13);
        boolean txnNull = rs.wasNull();
        long disp = rs.getLong(5);
        Long dispositionId = rs.wasNull() ? null : disp;
        return new WritebackTask(rs.getLong(1), rs.getString(2), Action.valueOf(rs.getString(3)), rs.getString(4), dispositionId,
            rs.getString(6), Quantities.format(rs.getBigDecimal(7)), rs.getString(8), status, rs.getInt(10),
            // 契约：非 PENDING 时 nextAttemptAt 为 null。
            status == WritebackStatus.PENDING && next != null ? next.toInstant() : null,
            rs.getString(12), txnNull ? null : txn, rs.getTimestamp(14).toInstant(), rs.getTimestamp(15).toInstant());
    };

    // ---------------- 任务写入（与业务同事务） ----------------

    /** NCR 以 OPEN 建成：写 HOLD 任务（入 QC-HOLD）。报工前已冲销而直接 VOIDED 的 NCR 不调用本方法。 */
    @Transactional(propagation = Propagation.MANDATORY)
    public void enqueueHold(String ncrNo, String materialCode, String lotNo, String uom, BigDecimal qty) {
        insert(ncrNo, Action.HOLD, null, null, "qc:" + ncrNo + ":hold", materialCode, lotNo, uom, qty, WritebackStatus.PENDING);
    }

    /**
     * 登记处置行：SCRAP → 报废出库；REWORK / CONCESSION → 放行出库（reason 同处置类型，不指定目标库位）。
     * @param dispositionId 处置行在 NCR 内的编号（line_no，只追加、不复用），作为库存请求的 dispositionId 与幂等键的一部分
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void enqueueDisposition(String ncrNo, long dispositionId, DispositionType type, String materialCode, String lotNo,
                                   String uom, BigDecimal qty) {
        boolean scrap = type == DispositionType.SCRAP;
        insert(ncrNo, scrap ? Action.SCRAP : Action.RELEASE, scrap ? null : type.name(), dispositionId,
            "qc:" + ncrNo + ":disp:" + dispositionId + (scrap ? ":scrap" : ":release"), materialCode, lotNo, uom, qty, WritebackStatus.PENDING);
    }

    /**
     * NCR 因冲销转 VOIDED（调用方已持有 NCR 行锁，且该 NCR 无处置行，因此最多只有一条 HOLD 任务）：
     * <ul>
     *   <li>HOLD 仍 PENDING 且从未被领取（first_attempted_at 为空，即 attempts=0 且不在执行中）→ HOLD 置 CANCELLED，不写 VOID；</li>
     *   <li>否则保留 HOLD 并追加 VOID（排在 HOLD 之后串行执行）：HOLD 已被明确拒绝（FAILED/REJECTED）或已人工结案时，
     *       库存确定未记账或已线下处理，VOID 直接以 CANCELLED 落库留痕；其余情况（DONE、执行中、退避中、结果不确定的 FAILED）VOID 为 PENDING。</li>
     * </ul>
     * HOLD 行先 {@code FOR UPDATE}：调度器领取 HOLD 的事务（领取即记 first_attempted_at）与本事务互斥，不会出现“判定未尝试后又被领取”。
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void onVoided(String ncrNo) {
        List<Object[]> holds = jdbc.query("select id, ncr_no, status, first_attempted_at, failure_kind, material_code, lot_no, uom, qty"
                + " from inventory_writeback where ncr_no = ? and action = 'HOLD' for update",
            (rs, i) -> new Object[] {rs.getLong(1), rs.getString(2), WritebackStatus.valueOf(rs.getString(3)), rs.getTimestamp(4),
                rs.getString(5), rs.getString(6), rs.getString(7), rs.getString(8), rs.getBigDecimal(9)}, ncrNo);
        if (holds.isEmpty()) return;
        Object[] h = holds.get(0);
        WritebackStatus status = (WritebackStatus) h[2];
        Timestamp now = now();
        if (status == WritebackStatus.PENDING && h[3] == null) {
            jdbc.update("update inventory_writeback set status = 'CANCELLED', next_attempt_at = null, updated_at = ? where id = ?", now, h[0]);
            metrics.writeback(QualityMetrics.WRITEBACK_CANCELLED);
            return;
        }
        boolean cancelled = status == WritebackStatus.RESOLVED_MANUALLY || status == WritebackStatus.CANCELLED
            || (status == WritebackStatus.FAILED && REJECTED.equals(h[4]));
        insert(ncrNo, Action.VOID, NCR_VOIDED, null, "qc:" + ncrNo + ":void", (String) h[5], (String) h[6], (String) h[7],
            (BigDecimal) h[8], cancelled ? WritebackStatus.CANCELLED : WritebackStatus.PENDING);
        if (cancelled) metrics.writeback(QualityMetrics.WRITEBACK_CANCELLED);
    }

    /** 关闭前校验：该 NCR 仍有 PENDING 或 FAILED 任务 → 409 writeback-pending（DONE / CANCELLED / RESOLVED_MANUALLY 不阻止关闭）。 */
    @Transactional(propagation = Propagation.MANDATORY)
    public void assertSettled(String ncrNo) {
        Integer open = jdbc.queryForObject("select count(*) from inventory_writeback where ncr_no = ? and status in ('PENDING', 'FAILED')",
            Integer.class, ncrNo);
        if (open != null && open > 0)
            throw new ProblemException(ProblemType.WRITEBACK_PENDING,
                "NCR " + ncrNo + " 仍有 " + open + " 条未完成（PENDING/FAILED）的库存回写任务，完成、重试成功或人工结案后才能关闭");
    }

    // ---------------- 查询与人工操作 ----------------

    /** 按创建顺序返回 NCR 的全部回写任务；NCR 不存在返回 404 ncr-not-found。 */
    public List<WritebackTask> list(String ncrNo) {
        requireNcr(ncrNo);
        return jdbc.query("select " + COLUMNS + " from inventory_writeback where ncr_no = ? order by id", TASK, ncrNo);
    }

    /**
     * 人工重试：仅 FAILED，且任务创建时间在重试窗口（mes.quality.writeback.retry-window，默认 P6D）之内；
     * 置回 PENDING、attempts 清零、立即排队，幂等键不变。first_attempted_at 不清空，作废判定仍视为“已尝试过”。
     * 已作废 NCR 的 HOLD，若其后的 VOID 已因 HOLD 被明确拒绝而取消，则不允许重试（重试成功会留下无人扣回的隔离量），只能人工结案。
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public WritebackTask retry(String ncrNo, long id) {
        Locked t = lock(ncrNo, id);
        if (t.status() != WritebackStatus.FAILED)
            throw transition(id, t.status(), "重试（仅 FAILED 可重试）");
        Instant now = clock.instant();
        if (t.createdAt().plus(props.retryWindow()).isBefore(now))
            throw new ProblemException(ProblemType.INVALID_STATUS_TRANSITION, "回写任务 " + id + " 创建已超过重试窗口 " + props.retryWindow()
                + "（库存幂等记录只保留 7 天，超期重放可能重复记账），请人工核对库存后结案");
        if (t.action() == Action.HOLD) {
            Integer cancelledVoid = jdbc.queryForObject("select count(*) from inventory_writeback where ncr_no = ? and action = 'VOID'"
                + " and status = 'CANCELLED'", Integer.class, ncrNo);
            if (cancelledVoid != null && cancelledVoid > 0)
                throw new ProblemException(ProblemType.INVALID_STATUS_TRANSITION,
                    "NCR " + ncrNo + " 已作废且作废扣回已取消，不能再重试入隔离，请人工结案");
        }
        Timestamp ts = now();
        jdbc.update("update inventory_writeback set status = 'PENDING', attempts = 0, failure_kind = null, next_attempt_at = ?, updated_at = ?"
            + " where id = ?", ts, ts, id);
        return get(id);
    }

    /**
     * 人工结案：仅 FAILED；reason 必填（空白 400）；置终态 RESOLVED_MANUALLY 并写审计（操作人、原因、时间）。
     * HOLD 被结案时，排在其后的 PENDING VOID 置 CANCELLED（人工已确认库存未记账或已线下处理）。
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public WritebackTask resolve(String ncrNo, long id, String operator, String reason) {
        if (reason == null || reason.isBlank()) throw ProblemException.validation("reason 必填且不能为空白");
        if (reason.length() > 512) throw ProblemException.validation("reason 不超过 512 个字符");
        Locked t = lock(ncrNo, id);
        if (t.status() != WritebackStatus.FAILED)
            throw transition(id, t.status(), "人工结案（仅 FAILED 可结案）");
        Timestamp ts = now();
        jdbc.update("update inventory_writeback set status = 'RESOLVED_MANUALLY', next_attempt_at = null, resolved_by = ?, resolved_reason = ?,"
            + " resolved_at = ?, updated_at = ? where id = ?", operator, reason.strip(), ts, ts, id);
        metrics.writeback(QualityMetrics.WRITEBACK_RESOLVED_MANUALLY);
        if (t.action() == Action.HOLD) cancelPendingVoid(ncrNo);
        return get(id);
    }

    /** 取消该 NCR 仍 PENDING 的 VOID 任务（HOLD 被明确拒绝或人工结案后调用，调用方已在事务内）。 */
    int cancelPendingVoid(String ncrNo) {
        int n = jdbc.update("update inventory_writeback set status = 'CANCELLED', next_attempt_at = null, updated_at = ?"
            + " where ncr_no = ? and action = 'VOID' and status = 'PENDING'", now(), ncrNo);
        for (int i = 0; i < n; i++) metrics.writeback(QualityMetrics.WRITEBACK_CANCELLED);
        return n;
    }

    /** PENDING 任务数（含退避中与执行中），供 mes_quality_writeback_pending。 */
    public long pendingCount() {
        Long n = jdbc.queryForObject("select count(*) from inventory_writeback where status = 'PENDING'", Long.class);
        return n == null ? 0 : n;
    }

    /**
     * 删除超过保留期（默认 P30D，按 updated_at）的终态任务（DONE / CANCELLED / RESOLVED_MANUALLY），每批 batchSize 行一个语句，最多 maxBatches 批。
     * FAILED 与 PENDING 永不清理。由 03:47 的清理任务调用。
     */
    public int purgeTerminal(int batchSize, int maxBatches) {
        Timestamp cutoff = Timestamp.from(clock.instant().minus(props.retention()).truncatedTo(ChronoUnit.MILLIS));
        int total = 0;
        for (int b = 0; b < maxBatches; b++) {
            List<Long> ids = jdbc.queryForList("select id from inventory_writeback where status in ('DONE', 'CANCELLED', 'RESOLVED_MANUALLY')"
                + " and updated_at < ? order by id limit " + batchSize, Long.class, cutoff);
            if (ids.isEmpty()) break;
            String in = String.join(",", java.util.Collections.nCopies(ids.size(), "?"));
            List<Object> args = new java.util.ArrayList<>(ids);
            args.add(cutoff);
            total += jdbc.update("delete from inventory_writeback where id in (" + in + ") and updated_at < ?"
                + " and status in ('DONE', 'CANCELLED', 'RESOLVED_MANUALLY')", args.toArray());
            if (ids.size() < batchSize) break;
        }
        return total;
    }

    // ---------------- 内部工具 ----------------

    private record Locked(Action action, WritebackStatus status, Instant createdAt) {}

    /** 锁定属于该 NCR 的任务；NCR 或任务不存在、任务不属于该 NCR 都返回 404 ncr-not-found（契约复用该类型）。 */
    private Locked lock(String ncrNo, long id) {
        requireNcr(ncrNo);
        List<Locked> rows = jdbc.query("select action, status, created_at from inventory_writeback where id = ? and ncr_no = ? for update",
            (rs, i) -> new Locked(Action.valueOf(rs.getString(1)), WritebackStatus.valueOf(rs.getString(2)), rs.getTimestamp(3).toInstant()),
            id, ncrNo);
        if (rows.isEmpty()) throw new ProblemException(ProblemType.NCR_NOT_FOUND, "NCR " + ncrNo + " 下不存在回写任务 " + id);
        return rows.get(0);
    }

    private void requireNcr(String ncrNo) {
        if (ncrNo == null || !NcrService.NCR_NO.matcher(ncrNo).matches()) throw ProblemException.notFound(ncrNo);
        Integer n = jdbc.queryForObject("select count(*) from ncr where ncr_no = ?", Integer.class, ncrNo);
        if (n == null || n == 0) throw ProblemException.notFound(ncrNo);
    }

    WritebackTask get(long id) {
        return jdbc.queryForObject("select " + COLUMNS + " from inventory_writeback where id = ?", TASK, id);
    }

    private static ProblemException transition(long id, WritebackStatus status, String action) {
        return new ProblemException(ProblemType.INVALID_STATUS_TRANSITION, "回写任务 " + id + " 当前状态为 " + status + "，不能" + action);
    }

    private void insert(String ncrNo, Action action, String reason, Long dispositionId, String key, String materialCode, String lotNo,
                        String uom, BigDecimal qty, WritebackStatus status) {
        Timestamp now = now();
        jdbc.update("insert into inventory_writeback (ncr_no, action, reason, disposition_id, idempotency_key, material_code, lot_no, uom, qty,"
                + " status, attempts, next_attempt_at, created_at, updated_at) values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 0, ?, ?, ?)",
            ncrNo, action.name(), reason, dispositionId, key, materialCode, lotNo, uom, qty, status.name(),
            status == WritebackStatus.PENDING ? now : null, now, now);
    }

    /** 当前时刻截断到毫秒（向下取整）：列为 TIMESTAMP(3)，避免数据库四舍五入后晚于写入时刻、同一毫秒内领取不到（#46）。 */
    private Timestamp now() {
        return Timestamp.from(clock.instant().truncatedTo(ChronoUnit.MILLIS));
    }
}
