package com.smartmanufacturing.quality;

import com.smartmanufacturing.quality.NcrDtos.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * NCR 领域服务：查询、评审、部分处置、关闭，以及供事件消费使用的建单与作废。
 * <ul>
 *   <li>写操作都要求调用方已开启事务（MANDATORY）：HTTP 写入经 {@link IdempotencyService}（READ COMMITTED），
 *       事件消费经 {@link DefectEventHandler}，状态变更、处置行、outbox 事件同一事务提交。</li>
 *   <li>状态判断前先 {@code SELECT ... FOR UPDATE} 锁 NCR 行，同一 NCR 的并发处置串行执行，累计量不会超过不良量。</li>
 *   <li>本期所有处置（含 SCRAP）只登记记录，<b>不调用库存服务、不改动库存</b>。</li>
 *   <li>version 每次状态或处置变化 +1，即事件 aggregateVersion。</li>
 * </ul>
 */
@Service
public class NcrService {
    /** 与契约 NcrNo 参数的 pattern 一致；格式不符的编号必然不存在，直接按 ncr-not-found 处理。 */
    static final Pattern NCR_NO = Pattern.compile("^NCR-\\d{8}-\\d{4,}$");
    private static final String NCR_COLUMNS = "ncr_no, status, source_report_id, work_order_no, operation_no, material_code, lot_no, uom,"
        + " defect_qty, disposed_qty, void_reason, created_at, version";

    private final JdbcTemplate jdbc;
    private final OutboxWriter outbox;
    private final QualityMetrics metrics;
    private final NcrNumberGenerator numbers;
    private final Clock clock;

    public NcrService(JdbcTemplate jdbc, OutboxWriter outbox, QualityMetrics metrics, NcrNumberGenerator numbers, Clock clock) {
        this.jdbc = jdbc; this.outbox = outbox; this.metrics = metrics; this.numbers = numbers; this.clock = clock;
    }

    /** NCR 行（不含处置行）。 */
    private record Row(String ncrNo, Status status, String sourceReportId, String workOrderNo, String operationNo, String materialCode,
                       String lotNo, String uom, BigDecimal defectQty, BigDecimal disposedQty, VoidReason voidReason,
                       Instant createdAt, long version) {}

    private static final RowMapper<Row> ROW = (rs, i) -> new Row(rs.getString(1), Status.valueOf(rs.getString(2)), rs.getString(3),
        rs.getString(4), rs.getString(5), rs.getString(6), rs.getString(7), rs.getString(8), rs.getBigDecimal(9), rs.getBigDecimal(10),
        rs.getString(11) == null ? null : VoidReason.valueOf(rs.getString(11)), rs.getTimestamp(12).toInstant(), rs.getLong(13));

    // ---------------- 查询 ----------------

    /** 查询 NCR 详情（含全部处置行），不存在返回 404 ncr-not-found。 */
    public Ncr get(String ncrNo) {
        return toDto(load(ncrNo, false));
    }

    /** 分页查询 NCR，按创建时间倒序（同一时刻按 id 倒序，保证分页稳定）。 */
    public Page<Ncr> list(String status, String workOrderNo, int page, int size) {
        checkPage(page, size);
        StringBuilder where = new StringBuilder(" where 1 = 1");
        List<Object> args = new ArrayList<>();
        if (status != null) {
            where.append(" and status = ?");
            args.add(parseEnum(Status.class, "status", status).name());
        }
        if (workOrderNo != null && !workOrderNo.isBlank()) { where.append(" and work_order_no = ?"); args.add(workOrderNo); }
        Long total = jdbc.queryForObject("select count(*) from ncr" + where, Long.class, args.toArray());
        List<Row> rows = jdbc.query("select " + NCR_COLUMNS + " from ncr" + where + " order by created_at desc, id desc"
            + " limit " + size + " offset " + ((long) page * size), ROW, args.toArray());
        return new Page<>(rows.stream().map(this::toDto).toList(), page, size, total == null ? 0 : total);
    }

    /** 分页查询暂存冲销，按收到时间倒序。 */
    public Page<PendingReversal> pendingReversals(String status, int page, int size) {
        checkPage(page, size);
        String where = status == null ? "" : " where status = ?";
        Object[] args = status == null ? new Object[0] : new Object[] {parseEnum(PendingStatus.class, "status", status).name()};
        Long total = jdbc.queryForObject("select count(*) from pending_reversal" + where, Long.class, args);
        List<PendingReversal> items = jdbc.query("select reversed_report_id, reversal_id, status, received_at, ncr_no from pending_reversal"
                + where + " order by received_at desc, reversed_report_id limit " + size + " offset " + ((long) page * size),
            (rs, i) -> new PendingReversal(rs.getString(1), rs.getString(2), PendingStatus.valueOf(rs.getString(3)),
                rs.getTimestamp(4).toInstant(), rs.getString(5)), args);
        return new Page<>(items, page, size, total == null ? 0 : total);
    }

    /** 分页查询冲销冲突，按记录时间倒序。 */
    public Page<Conflict> conflicts(int page, int size) {
        checkPage(page, size);
        Long total = jdbc.queryForObject("select count(*) from ncr_conflict", Long.class);
        List<Conflict> items = jdbc.query("select event_id, type, ncr_no, reversed_report_id, reversal_id, disposed_qty, recorded_at"
                + " from ncr_conflict order by recorded_at desc, id desc limit " + size + " offset " + ((long) page * size),
            (rs, i) -> new Conflict(rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4), rs.getString(5),
                Quantities.format(rs.getBigDecimal(6)), rs.getTimestamp(7).toInstant()));
        return new Page<>(items, page, size, total == null ? 0 : total);
    }

    // ---------------- 人工操作（经幂等执行器，READ COMMITTED） ----------------

    /** 开始评审：OPEN → UNDER_REVIEW；已是 UNDER_REVIEW 返回当前状态（幂等）；作废 409 ncr-voided；其他 409 invalid-status-transition。 */
    @Transactional(propagation = Propagation.MANDATORY)
    public Ncr review(String ncrNo) {
        Row ncr = load(ncrNo, true);
        if (ncr.status() == Status.UNDER_REVIEW) return toDto(ncr);
        rejectVoided(ncr);
        if (ncr.status() != Status.OPEN) throw transition(ncr, "评审");
        updateStatus(ncr, Status.UNDER_REVIEW);
        return get(ncrNo);
    }

    /**
     * 登记（部分）处置：仅 UNDER_REVIEW；uom 必须等于 NCR 单位（422 uom-mismatch）；数量不得超过剩余待处置量（422 over-dispose）。
     * 累计量等于不良量时自动转 DISPOSED。每条处置行发一次 NcrDisposed。SCRAP 等所有类型都不改动库存。
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public Ncr dispose(String ncrNo, String operator, CreateDisposition request) {
        DispositionType type = parseEnum(DispositionType.class, "type", request.type());
        BigDecimal qty = Quantities.parsePositive("qty", request.qty());
        if (request.uom() == null || request.uom().isBlank() || request.uom().length() > 16)
            throw ProblemException.validation("uom 必填且不超过 16 个字符");
        if (request.reason() != null && request.reason().length() > 500) throw ProblemException.validation("reason 不超过 500 个字符");

        Row ncr = load(ncrNo, true);
        rejectVoided(ncr);
        if (ncr.status() != Status.UNDER_REVIEW) throw transition(ncr, "登记处置");
        if (!ncr.uom().equals(request.uom()))
            throw new ProblemException(ProblemType.UOM_MISMATCH, "NCR " + ncrNo + " 的单位为 " + ncr.uom() + "，与处置单位 " + request.uom() + " 不一致");
        BigDecimal remaining = ncr.defectQty().subtract(ncr.disposedQty());
        if (qty.compareTo(remaining) > 0)
            throw new ProblemException(ProblemType.OVER_DISPOSE, "本次处置 " + Quantities.format(qty) + " 超过剩余待处置量 " + Quantities.format(remaining));

        Instant now = clock.instant();
        String dispositionId = UUID.randomUUID().toString();
        Integer lines = jdbc.queryForObject("select count(*) from ncr_disposition where ncr_no = ?", Integer.class, ncrNo);
        jdbc.update("insert into ncr_disposition (disposition_id, ncr_no, line_no, type, qty, uom, reason, operator, disposed_at)"
                + " values (?, ?, ?, ?, ?, ?, ?, ?, ?)", dispositionId, ncrNo, (lines == null ? 0 : lines) + 1, type.name(), qty,
            ncr.uom(), request.reason(), operator, Timestamp.from(now));
        BigDecimal disposed = ncr.disposedQty().add(qty);
        Status next = disposed.compareTo(ncr.defectQty()) == 0 ? Status.DISPOSED : Status.UNDER_REVIEW;
        jdbc.update("update ncr set disposed_qty = ?, status = ?, version = version + 1, updated_at = ? where ncr_no = ?",
            disposed, next.name(), Timestamp.from(now), ncrNo);
        Map<String, Object> extra = new LinkedHashMap<>();
        extra.put("disposition", new Disposition(dispositionId, type, Quantities.format(qty), ncr.uom(), request.reason(), operator, now));
        extra.put("disposedQty", Quantities.format(disposed));
        extra.put("defectQty", Quantities.format(ncr.defectQty()));
        outbox.append("NcrDisposed", ncrNo, extra);
        metrics.disposition(type.name(), ncr.uom(), qty);
        if (next == Status.DISPOSED) metrics.transition(Status.UNDER_REVIEW.name(), Status.DISPOSED.name());
        return get(ncrNo);
    }

    /** 关闭：DISPOSED → CLOSED；已 CLOSED 返回当前状态（幂等）；作废 409 ncr-voided；其他 409 invalid-status-transition。 */
    @Transactional(propagation = Propagation.MANDATORY)
    public Ncr close(String ncrNo) {
        Row ncr = load(ncrNo, true);
        if (ncr.status() == Status.CLOSED) return toDto(ncr);
        rejectVoided(ncr);
        if (ncr.status() != Status.DISPOSED) throw transition(ncr, "关闭");
        updateStatus(ncr, Status.CLOSED);
        outbox.append("NcrClosed", ncrNo, Map.of());
        return get(ncrNo);
    }

    // ---------------- 事件消费使用 ----------------

    /** 报工不良的内容（来自 DefectReported）。 */
    public record DefectReport(String reportId, String workOrderNo, String operationNo, String materialCode, String lotNo,
                               String uom, BigDecimal defectQty) {}

    /** 按报工建 OPEN 的 NCR 并发 NcrOpened。 */
    @Transactional(propagation = Propagation.MANDATORY)
    public String open(DefectReport report) {
        String ncrNo = insert(report, Status.OPEN, null, null, null);
        Map<String, Object> extra = new LinkedHashMap<>();
        extra.put("materialCode", report.materialCode());
        extra.put("uom", report.uom());
        extra.put("defectQty", Quantities.format(report.defectQty()));
        outbox.append("NcrOpened", ncrNo, extra);
        metrics.transition(QualityMetrics.FROM_NONE, Status.OPEN.name());
        return ncrNo;
    }

    /** 冲销先于报工到达、报工在 TTL 内到达：直接建 VOIDED（REVERSED_BEFORE_REPORTED）的 NCR，只发 NcrVoided。 */
    @Transactional(propagation = Propagation.MANDATORY)
    public String openVoided(DefectReport report, String reversalId) {
        String ncrNo = insert(report, Status.VOIDED, VoidReason.REVERSED_BEFORE_REPORTED, report.reportId(), reversalId);
        outbox.append("NcrVoided", ncrNo, voidedExtra(report.reportId(), reversalId, VoidReason.REVERSED_BEFORE_REPORTED));
        metrics.transition(QualityMetrics.FROM_NONE, Status.VOIDED.name());
        return ncrNo;
    }

    /** 未处置的 NCR（OPEN/UNDER_REVIEW 且无处置行）被冲销：转 VOIDED（REVERSED）并发 NcrVoided。调用方已持有 NCR 行锁并检查过状态。 */
    @Transactional(propagation = Propagation.MANDATORY)
    public void voidByReversal(String ncrNo, Status from, String reversedReportId, String reversalId) {
        jdbc.update("update ncr set status = 'VOIDED', void_reason = 'REVERSED', reversed_report_id = ?, reversal_id = ?,"
            + " version = version + 1, updated_at = ? where ncr_no = ?", reversedReportId, reversalId, Timestamp.from(clock.instant()), ncrNo);
        outbox.append("NcrVoided", ncrNo, voidedExtra(reversedReportId, reversalId, VoidReason.REVERSED));
        metrics.transition(from.name(), Status.VOIDED.name());
    }

    private String insert(DefectReport r, Status status, VoidReason reason, String reversedReportId, String reversalId) {
        String ncrNo = numbers.next();
        Timestamp now = Timestamp.from(clock.instant());
        jdbc.update("insert into ncr (ncr_no, source_report_id, work_order_no, operation_no, material_code, lot_no, uom, defect_qty,"
                + " disposed_qty, status, void_reason, reversed_report_id, reversal_id, version, created_at, updated_at)"
                + " values (?, ?, ?, ?, ?, ?, ?, ?, 0, ?, ?, ?, ?, 1, ?, ?)",
            ncrNo, r.reportId(), r.workOrderNo(), r.operationNo(), r.materialCode(), r.lotNo(), r.uom(), r.defectQty(),
            status.name(), reason == null ? null : reason.name(), reversedReportId, reversalId, now, now);
        return ncrNo;
    }

    private static Map<String, Object> voidedExtra(String reversedReportId, String reversalId, VoidReason reason) {
        Map<String, Object> extra = new LinkedHashMap<>();
        extra.put("reversedReportId", reversedReportId);
        extra.put("reversalId", reversalId);
        extra.put("voidReason", reason.name());
        return extra;
    }

    // ---------------- 内部工具 ----------------

    private void updateStatus(Row ncr, Status to) {
        jdbc.update("update ncr set status = ?, version = version + 1, updated_at = ? where ncr_no = ?",
            to.name(), Timestamp.from(clock.instant()), ncr.ncrNo());
        metrics.transition(ncr.status().name(), to.name());
    }

    private static void rejectVoided(Row ncr) {
        if (ncr.status() == Status.VOIDED)
            throw new ProblemException(ProblemType.NCR_VOIDED, "NCR " + ncr.ncrNo() + " 已作废，不能再评审、处置或关闭");
    }

    private static ProblemException transition(Row ncr, String action) {
        return new ProblemException(ProblemType.INVALID_STATUS_TRANSITION, "NCR " + ncr.ncrNo() + " 当前状态为 " + ncr.status() + "，不能" + action);
    }

    /** 读取 NCR 行；forUpdate 时加行锁。编号格式不符或不存在均返回 404 ncr-not-found。 */
    private Row load(String ncrNo, boolean forUpdate) {
        if (ncrNo == null || !NCR_NO.matcher(ncrNo).matches()) throw ProblemException.notFound(ncrNo);
        List<Row> rows = jdbc.query("select " + NCR_COLUMNS + " from ncr where ncr_no = ?" + (forUpdate ? " for update" : ""), ROW, ncrNo);
        if (rows.isEmpty()) throw ProblemException.notFound(ncrNo);
        return rows.get(0);
    }

    private Ncr toDto(Row r) {
        List<Disposition> dispositions = jdbc.query("select disposition_id, type, qty, uom, reason, operator, disposed_at from ncr_disposition"
            + " where ncr_no = ? order by line_no", NcrService::disposition, r.ncrNo());
        return new Ncr(r.ncrNo(), r.status(), r.sourceReportId(), r.workOrderNo(), r.operationNo(), r.materialCode(), r.lotNo(), r.uom(),
            Quantities.format(r.defectQty()), Quantities.format(r.disposedQty()), r.voidReason(), dispositions, r.createdAt(), r.version());
    }

    private static Disposition disposition(ResultSet rs, int i) throws SQLException {
        return new Disposition(rs.getString(1), DispositionType.valueOf(rs.getString(2)), Quantities.format(rs.getBigDecimal(3)),
            rs.getString(4), rs.getString(5), rs.getString(6), rs.getTimestamp(7).toInstant());
    }

    private static void checkPage(int page, int size) {
        if (page < 0) throw ProblemException.validation("page 不能小于 0");
        if (size < 1 || size > 200) throw ProblemException.validation("size 取值 1–200");
    }

    private static <E extends Enum<E>> E parseEnum(Class<E> type, String field, String raw) {
        try {
            if (raw == null) throw new IllegalArgumentException();
            return Enum.valueOf(type, raw);
        } catch (IllegalArgumentException e) {
            throw ProblemException.validation(field + " 取值非法：" + raw);
        }
    }
}
