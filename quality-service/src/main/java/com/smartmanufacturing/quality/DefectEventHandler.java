package com.smartmanufacturing.quality;

import com.fasterxml.jackson.databind.JsonNode;
import com.smartmanufacturing.quality.NcrDtos.PendingStatus;
import com.smartmanufacturing.quality.NcrDtos.Status;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Clock;
import java.util.List;

/**
 * 不良品事件处理（与 RocketMQ 解耦，便于单测直接调用）：一条消息一个本地事务（READ COMMITTED）。
 * <ol>
 *   <li>先插入 consumed_event(event_id)：主键冲突即重复投递，返回 DUPLICATE（由消费入口 ACK）。</li>
 *   <li>再锁报工级串行锁（defect_report_lock），同一报工的报告与冲销并发消费时串行执行，避免双方互相看不到而留下孤儿暂存。</li>
 *   <li>按 tag 处理：DefectReported 建 NCR 或对冲暂存；DefectReversed 作废、暂存或记录冲突。</li>
 * </ol>
 * 处理中抛出的异常整笔回滚（含 consumed_event），由消费入口返回 RECONSUME_LATER 让 Broker 重投。
 */
@Component
public class DefectEventHandler {
    /** 消费结果，对应 mes_quality_event_consumed_total 的 result 标签。 */
    public enum Result {
        OK("ok"), DUPLICATE("duplicate"), PENDING("pending"), MATCHED("matched"), DISPOSED_REVERSAL_CONFLICT(QualityMetrics.DISPOSED_REVERSAL_CONFLICT);
        private final String metric;
        Result(String metric) { this.metric = metric; }
        public String metric() { return metric; }
    }

    /**
     * 事件内容不合法（缺 eventId、缺字段、数量格式错误、未知 tag 等）：重试也不会成功（#30），
     * 消费入口记录到 invalid_event、计 result=invalid 后直接 ACK，不再重投。抛出时所在事务整体回滚（含 consumed_event），不留任何业务写入。
     */
    public static class InvalidEventException extends RuntimeException {
        public InvalidEventException(String message) { super(message); }
    }

    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;
    private final NcrService ncrs;
    private final MqProperties mq;
    private final Clock clock;

    public DefectEventHandler(JdbcTemplate jdbc, PlatformTransactionManager txManager, NcrService ncrs, MqProperties mq, Clock clock) {
        this.jdbc = jdbc; this.ncrs = ncrs; this.mq = mq; this.clock = clock;
        this.tx = new TransactionTemplate(txManager);
        // READ COMMITTED：拿到报工锁后每条语句读取最新已提交数据（看得到并发事务刚提交的 NCR 或暂存），与 HTTP 写事务一致。
        this.tx.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
    }

    /**
     * 处理一条不良品事件。
     * @param tag     消息 tag（DefectReported / DefectReversed，取值来自配置）
     * @param eventId 事件 ID（幂等键）
     * @param body    事件 JSON
     */
    public Result handle(String tag, String eventId, JsonNode body) {
        if (eventId == null || eventId.isBlank()) throw new InvalidEventException("缺少 eventId");
        if (eventId.length() > 64) throw new InvalidEventException("eventId 超过 64 个字符");
        boolean reported = mq.defectReportedTag().equals(tag);
        if (!reported && !mq.defectReversedTag().equals(tag)) throw new InvalidEventException("未知 tag：" + tag);
        return LockRetry.run(() -> tx.execute(status -> {
            try {
                jdbc.update("insert into consumed_event (event_id, tag, consumed_at) values (?, ?, ?)", eventId, tag, Timestamp.from(clock.instant()));
            } catch (DuplicateKeyException duplicate) {
                return Result.DUPLICATE;
            }
            return reported ? reported(body) : reversed(eventId, body);
        }));
    }

    /** DefectReported：命中 WAITING 暂存则建 VOIDED 并标 MATCHED；否则（无暂存或已 EXPIRED）建 OPEN。同一报工已有 NCR 视为重复。 */
    private Result reported(JsonNode body) {
        NcrService.DefectReport report = new NcrService.DefectReport(required(body, "reportId"), required(body, "workOrderNo"),
            required(body, "operationNo"), required(body, "materialCode"), optional(body, "lotNo"), required(body, "uom"), defectQty(body));
        if (report.uom().length() > 16) throw new InvalidEventException("uom 超过 16 个字符");
        lockReport(report.reportId());
        if (ncrExists(report.reportId())) return Result.DUPLICATE;
        List<String[]> pending = jdbc.query("select status, reversal_id from pending_reversal where reversed_report_id = ? for update",
            (rs, i) -> new String[] {rs.getString(1), rs.getString(2)}, report.reportId());
        if (!pending.isEmpty() && PendingStatus.WAITING.name().equals(pending.get(0)[0])) {
            String ncrNo = ncrs.openVoided(report, pending.get(0)[1]);
            jdbc.update("update pending_reversal set status = 'MATCHED', ncr_no = ?, updated_at = ? where reversed_report_id = ?",
                ncrNo, Timestamp.from(clock.instant()), report.reportId());
            return Result.MATCHED;
        }
        // 暂存已 EXPIRED：按正常报工建 OPEN 的 NCR，暂存保持 EXPIRED，由人工核对，不自动对冲。
        ncrs.open(report);
        return Result.OK;
    }

    /** DefectReversed：只认 reversedReportId + reversalId。 */
    private Result reversed(String eventId, JsonNode body) {
        String reversedReportId = required(body, "reversedReportId");
        String reversalId = required(body, "reversalId");
        lockReport(reversedReportId);
        List<Object[]> rows = jdbc.query("select ncr_no, status, disposed_qty from ncr where source_report_id = ? for update",
            (rs, i) -> new Object[] {rs.getString(1), Status.valueOf(rs.getString(2)), rs.getBigDecimal(3)}, reversedReportId);
        if (rows.isEmpty()) {
            // 冲销先于原报工到达：暂存待对冲后 ACK，不阻塞消费位点；同一报工已有暂存（另一条冲销或换了 eventId 的重发）视为重复。
            Integer exists = jdbc.queryForObject("select count(*) from pending_reversal where reversed_report_id = ?", Integer.class, reversedReportId);
            if (exists != null && exists > 0) return Result.DUPLICATE;
            Timestamp now = Timestamp.from(clock.instant());
            jdbc.update("insert into pending_reversal (reversed_report_id, reversal_id, event_id, status, received_at, ncr_no, updated_at)"
                + " values (?, ?, ?, 'WAITING', ?, null, ?)", reversedReportId, reversalId, eventId, now, now);
            return Result.PENDING;
        }
        String ncrNo = (String) rows.get(0)[0];
        Status status = (Status) rows.get(0)[1];
        BigDecimal disposed = (BigDecimal) rows.get(0)[2];
        if (status == Status.VOIDED) return Result.DUPLICATE;
        if (disposed.signum() > 0) {
            // 已有处置：实物已返工或报废，NCR 不回退；同一冲销（reversalId）已记过冲突则视为重复。
            Integer recorded = jdbc.queryForObject("select count(*) from ncr_conflict where ncr_no = ? and reversal_id = ?",
                Integer.class, ncrNo, reversalId);
            if (recorded != null && recorded > 0) return Result.DUPLICATE;
            jdbc.update("insert into ncr_conflict (event_id, type, ncr_no, reversed_report_id, reversal_id, disposed_qty, recorded_at)"
                + " values (?, ?, ?, ?, ?, ?, ?)", eventId, NcrDtos.DISPOSED_REVERSAL_CONFLICT, ncrNo, reversedReportId, reversalId,
                disposed, Timestamp.from(clock.instant()));
            return Result.DISPOSED_REVERSAL_CONFLICT;
        }
        // 无处置行：OPEN 或 UNDER_REVIEW（DISPOSED/CLOSED 必有处置量 > 0，已在上面分支处理）。
        ncrs.voidByReversal(ncrNo, status, reversedReportId, reversalId);
        return Result.OK;
    }

    /** 报工级串行锁：首次出现时插入锁行（并发插入由主键裁决），随后 SELECT ... FOR UPDATE 持有到事务结束。 */
    private void lockReport(String reportId) {
        try {
            jdbc.update("insert into defect_report_lock (report_id, created_at) values (?, ?)", reportId, Timestamp.from(clock.instant()));
        } catch (DuplicateKeyException exists) {
            // 锁行已存在（或另一事务刚插入并提交），直接加锁。
        }
        jdbc.queryForObject("select report_id from defect_report_lock where report_id = ? for update", String.class, reportId);
    }

    private boolean ncrExists(String reportId) {
        Integer n = jdbc.queryForObject("select count(*) from ncr where source_report_id = ?", Integer.class, reportId);
        return n != null && n > 0;
    }

    private static String required(JsonNode body, String field) {
        String v = optional(body, field);
        if (v == null || v.isBlank()) throw new InvalidEventException("事件缺少字段 " + field);
        if (v.length() > 64) throw new InvalidEventException("字段 " + field + " 超过 64 个字符");
        return v;
    }

    private static String optional(JsonNode body, String field) {
        JsonNode n = body.get(field);
        return n == null || n.isNull() ? null : n.asText();
    }

    /** defectQty 以十进制字符串传输（也兼容数字），必须大于 0。 */
    private static BigDecimal defectQty(JsonNode body) {
        String raw = optional(body, "defectQty");
        try {
            return Quantities.parsePositive("defectQty", raw);
        } catch (ProblemException e) {
            throw new InvalidEventException(e.getMessage());
        }
    }
}
