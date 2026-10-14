package com.smartmanufacturing.workorder;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.smartmanufacturing.api.ApiResponse;
import com.smartmanufacturing.api.workorder.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Timestamp;
import java.time.Clock;
import java.util.*;
import java.util.function.Supplier;

/**
 * 工序报工、冲销与手工完工。
 * <p>并发模型：每个写命令都在单个本地事务内执行，第一步对 work_order 行 SELECT … FOR UPDATE，
 * 之后依次校验、更新累计、追加流水、写发件箱与幂等记录。同一工单的写命令因此完全串行，累计不会超限。
 * 只锁工单行、不锁 work_order_lifecycle，避免与冻结（先改 lifecycle 再条件更新 work_order）形成锁序相反的死锁。
 * <p>幂等：同一（调用方, Idempotency-Key）只记录首次成功处理的状态码与响应体，重放原样返回；失败不落记录，可用同键重试。
 */
@Service
public class OperationReportingService {
    private static final Logger LOG = LoggerFactory.getLogger(OperationReportingService.class);
    /** 数量统一按 6 位小数（DECIMAL(18,6)）存储与输出。 */
    static final int SCALE = 6;
    private static final BigDecimal ZERO = BigDecimal.ZERO.setScale(SCALE);
    /** 工时统一 2 位小数（DECIMAL(10,2)）。 */
    private static final BigDecimal HOURS_ZERO = BigDecimal.ZERO.setScale(2);

    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;
    private final WorkOrderJournal journal;
    private final ObjectMapper mapper;
    private final Clock clock;
    private final ReportingMetrics metrics;
    private final WorkOrderCancellation cancellation;
    private final WorkOrderClosure closure;
    private final OperationTaskStates tasks;
    private final String eventTopic, startedTag, completedTag, reopenedTag, qualityTopic, defectReportedTag, defectReversedTag;

    public OperationReportingService(JdbcTemplate jdbc, TransactionTemplate tx, WorkOrderJournal journal, ObjectMapper mapper,
                                     Clock clock, MqProperties mq, ReportingMetrics metrics, WorkOrderCancellation cancellation, WorkOrderClosure closure,
                                     OperationTaskStates tasks) {
        this.tasks = tasks;
        this.metrics = metrics;
        this.cancellation = cancellation;
        this.closure = closure;
        this.jdbc = jdbc; this.tx = tx; this.journal = journal; this.mapper = mapper; this.clock = clock;
        // 启动即解析，缺配置直接启动失败，而不是在第一次报工时才发现
        this.eventTopic = mq.topic("workorder-event");
        this.startedTag = mq.tag("workorder-started");
        this.completedTag = mq.tag("workorder-completed");
        this.reopenedTag = mq.tag("workorder-reopened");
        this.qualityTopic = mq.topic("workorder-quality");
        this.defectReportedTag = mq.tag("defect-reported");
        this.defectReversedTag = mq.tag("defect-reversed");
    }

    /** 命令执行结果：HTTP 状态、响应体（ApiResponse JSON）以及是否为幂等重放。 */
    public record CommandResult(int status, String body, boolean replayed) {}

    /** 已加锁的工单行快照（派工命令 OperationTaskService 也复用）。 */
    record Order(String id, String status, BigDecimal planned, String product) {}
    private record Progress(String opNo, int seq, BigDecimal good, BigDecimal defect, BigDecimal scrap, String uom, int effective, BigDecimal labor) {
        BigDecimal total() { return good.add(defect).add(scrap); }
    }
    private record Report(String reportId, String orderId, String type, String opNo, BigDecimal good, BigDecimal defect,
                          BigDecimal scrap, String uom, String lotNo, String note, String reversedReportId, String operator,
                          java.time.Instant createdAt, String station, BigDecimal labor) {}

    // ------------------------------------------------------------------ 报工

    public CommandResult report(String orderNo, String opNo, ReportOperationRequest req, String key, String operator) {
        String hash = hash("REPORT", orderNo, opNo, num(req.goodQty()), num(req.defectQty()), num(req.scrapQty()),
            req.uom(), req.lotNo(), req.note(), req.laborHours());
        // 业务异常（含 Problem type）由 WorkOrderProblemHandler 统一计数，这里只计成功、重放与非预期异常
        try {
            CommandResult r = idempotent(operator, key, "REPORT", orderNo, hash, 201, order -> doReport(order, opNo, req, operator));
            if (r.replayed()) metrics.reportReplay();
            else metrics.reportOk(req.goodQty(), req.defectQty(), req.scrapQty(), req.uom());
            return r;
        } catch (com.smartmanufacturing.common.BusinessException e) {
            throw e;
        } catch (RuntimeException e) {
            metrics.reportError();
            throw e;
        }
    }

    private OperationReportResult doReport(Order order, String opNo, ReportOperationRequest req, String operator) {
        switch (order.status()) {
            case "CLOSED" -> throw WorkOrderProblemType.WORKORDER_CLOSED.exception("工单 " + order.id() + " 已关闭，不能报工");
            case "CANCELLED" -> throw WorkOrderProblemType.WORKORDER_CANCELLED.exception("工单 " + order.id() + " 已取消，不能报工");
            case "RELEASED", "IN_PROGRESS" -> { }
            default -> throw WorkOrderProblemType.WORK_ORDER_STATE_CONFLICT.exception("工单当前状态 " + order.status() + " 不允许报工");
        }
        BigDecimal good = qty(req.goodQty()), defect = qty(req.defectQty()), scrap = qty(req.scrapQty());
        BigDecimal requested = good.add(defect).add(scrap);
        if (requested.signum() <= 0)
            throw WorkOrderProblemType.VALIDATION_ERROR.exception("合格、不良、报废数量合计必须大于 0");
        List<Progress> ops = progress(order.id());
        int k = indexOf(ops, opNo);
        Progress cur = ops.get(k);
        // M1 任务状态：报工不要求先派工（兼容已下达未派工的老数据）；暂停、已取消的任务拒绝报工
        OperationTaskStates.Task task = tasks.find(order.id(), opNo);
        if ("PAUSED".equals(task.status()) || "CANCELLED".equals(task.status()))
            throw WorkOrderProblemType.TASK_STATE_CONFLICT.exception("工序 " + opNo + " 当前任务状态 " + task.status() + " 不允许报工"
                + ("PAUSED".equals(task.status()) ? "，请先恢复" : ""), Map.of("operationNo", opNo, "taskStatus", task.status()));
        BigDecimal labor = hours(req.laborHours());
        String uom = ops.stream().map(Progress::uom).filter(Objects::nonNull).findFirst().orElse(null);
        if (uom != null && !uom.equals(req.uom()))
            throw WorkOrderProblemType.UOM_MISMATCH.exception("报工单位 " + req.uom() + " 与本工单已报工单位 " + uom + " 不一致",
                Map.of("expectedUom", uom, "requestedUom", req.uom()));
        BigDecimal limit = limit(order, ops, k);
        BigDecimal after = cur.total().add(requested);
        if (after.compareTo(limit) > 0) {
            LOG.info("报工超限：工单 {} 工序 {} 上限 {} 已报 {} 本次 {}", order.id(), opNo, num(limit), num(cur.total()), num(requested));
            throw WorkOrderProblemType.OVER_REPORT.exception("工序 " + opNo + " 累计报工将超过上限 " + num(limit),
                overData(opNo, limit, cur.total(), requested));
        }
        // 自动开工（PM 2026-10-14 确认）：READY（或已有流入但状态未推进的老数据 PENDING）报工直接 RUNNING
        if ("READY".equals(task.status()) || "PENDING".equals(task.status()))
            task = tasks.transition(task, "RUNNING", "START", "AUTO_START_BY_REPORT", operator);
        String reportId = UUID.randomUUID().toString();
        var now = clock.instant();
        // 批次号缺省为工单号（一个工单对应一个产出批次），落库与事件统一使用该值，保证质检回写库存时始终有批次
        String lotNo = effectiveLotNo(req.lotNo(), order.id());
        jdbc.update("insert into wo_operation_report (report_id, order_id, op_no, type, good_qty, defect_qty, scrap_qty, uom, lot_no, note, reversed_report_id, operator, request_id, created_at, station_code, labor_hours) values (?, ?, ?, 'REPORT', ?, ?, ?, ?, ?, ?, null, ?, ?, ?, ?, ?)",
            reportId, order.id(), opNo, good, defect, scrap, req.uom(), lotNo, req.note(), operator, MDC.get("requestId"), DbTimes.ts(now), task.station(), labor);
        // 报工记录写入当时的工位（未派工为 null），之后改派不会修改这条记录
        jdbc.update("update wo_operation_progress set good_qty = good_qty + ?, defect_qty = defect_qty + ?, scrap_qty = scrap_qty + ?, uom = ?, effective_reports = effective_reports + 1, labor_hours = labor_hours + ?, updated_at = ? where order_id = ? and op_no = ?",
            good, defect, scrap, req.uom(), labor, DbTimes.ts(now), order.id(), opNo);
        if (defect.signum() > 0)
            defectEvent(order, opNo, req.uom(), lotNo, defect, reportId, null, "DefectReported", defectReportedTag);

        String status = order.status();
        if ("RELEASED".equals(status)) {
            long v = changeStatus(order.id(), status, "IN_PROGRESS", "FIRST_REPORT", "首次报工 " + reportId, operator);
            // 字段：eventId、workOrderNo、occurredAt、aggregateVersion（eventId / occurredAt 由 journal 补充）
            journal.routedEvent(order.id(), "WorkOrderStarted", eventTopic, startedTag, Map.of("workOrderNo", order.id(), "aggregateVersion", v));
            status = "IN_PROGRESS";
        }
        List<Progress> updated = progress(order.id());
        // 结算任务状态：本道达到上限且上道完工 → DONE；下道有流入 → READY
        tasks.settle(order.id(), totals(order, updated), operator);
        Progress last = updated.get(updated.size() - 1);
        if (last.good().compareTo(order.planned()) >= 0) {
            long v = changeStatus(order.id(), status, "COMPLETED", "AUTO_COMPLETE", "末道累计合格数达到计划数", operator);
            journal.routedEvent(order.id(), "WorkOrderCompleted", eventTopic, completedTag, completedPayload(order, last.good(), last.uom(), false, null, v));
            status = "COMPLETED";
        }
        LOG.info("报工成功：工单 {} 工序 {} 报工 {} 合格 {} 不良 {} 报废 {} 工单状态 {}", order.id(), opNo, reportId, num(good), num(defect), num(scrap), status);
        return new OperationReportResult(reportView(findReport(reportId).orElseThrow()), progressView(order.id(), status, order.planned(), updated));
    }

    // ------------------------------------------------------------------ 冲销

    public CommandResult reverse(String orderNo, String reportId, ReverseReportRequest req, String key, String operator) {
        String hash = hash("REVERSAL", orderNo, reportId, req.reason());
        CommandResult r = idempotent(operator, key, "REVERSAL", orderNo, hash, 201, order -> doReverse(order, reportId, req.reason(), operator));
        if (!r.replayed()) {
            // 冲销数量取自本次冲销记录（负数），按绝对值计入 mes_workorder_reversal_qty，不影响 report_qty
            try {
                var rep = mapper.readTree(r.body()).at("/data/report");
                metrics.reversed(new BigDecimal(rep.get("goodQty").asText()), new BigDecimal(rep.get("defectQty").asText()),
                    new BigDecimal(rep.get("scrapQty").asText()), rep.get("uom").asText());
            } catch (JsonProcessingException e) {
                LOG.warn("冲销指标记录失败（不影响业务）", e);
            }
        }
        return r;
    }

    private OperationReportResult doReverse(Order order, String reportId, String reason, String operator) {
        switch (order.status()) {
            case "CLOSED" -> throw WorkOrderProblemType.WORKORDER_CLOSED.exception("工单 " + order.id() + " 已关闭，不能冲销");
            case "CANCELLED" -> throw WorkOrderProblemType.WORKORDER_CANCELLED.exception("工单 " + order.id() + " 已取消，不能冲销");
            case "IN_PROGRESS", "COMPLETED" -> { }
            default -> throw WorkOrderProblemType.WORK_ORDER_STATE_CONFLICT.exception("工单当前状态 " + order.status() + " 不允许冲销");
        }
        Report original = findReport(reportId).filter(r -> r.orderId().equals(order.id()))
            .orElseThrow(() -> WorkOrderProblemType.REPORT_NOT_FOUND.exception("工单 " + order.id() + " 下不存在报工 " + reportId));
        if (!"REPORT".equals(original.type()))
            throw WorkOrderProblemType.REPORT_ALREADY_REVERSED.exception("报工 " + reportId + " 本身是冲销记录，不能再冲销");
        Integer done = jdbc.queryForObject("select count(*) from wo_operation_report where reversed_report_id = ?", Integer.class, reportId);
        if (done != null && done > 0)
            throw WorkOrderProblemType.REPORT_ALREADY_REVERSED.exception("报工 " + reportId + " 已冲销，只允许冲销一次");

        List<Progress> ops = progress(order.id());
        int k = indexOf(ops, original.opNo());
        BigDecimal newGood = ops.get(k).good().subtract(original.good());
        if (k + 1 < ops.size()) {
            Progress down = ops.get(k + 1);
            if (down.total().compareTo(newGood) > 0) {
                LOG.info("冲销被拒：工单 {} 工序 {} 冲销后合格 {} 小于下游工序 {} 已报 {}", order.id(), original.opNo(), num(newGood), down.opNo(), num(down.total()));
                throw WorkOrderProblemType.DOWNSTREAM_REPORTED.exception("冲销后工序 " + original.opNo() + " 累计合格数 " + num(newGood)
                    + " 将小于下游工序 " + down.opNo() + " 已报数量 " + num(down.total()), Map.of(
                    "operationNo", original.opNo(), "goodAfterReversal", num(newGood),
                    "downstreamOperationNo", down.opNo(), "downstreamReported", num(down.total())));
            }
        }
        String reversalId = UUID.randomUUID().toString();
        var now = clock.instant();
        jdbc.update("insert into wo_operation_report (report_id, order_id, op_no, type, good_qty, defect_qty, scrap_qty, uom, lot_no, note, reversed_report_id, operator, request_id, created_at, station_code, labor_hours) values (?, ?, ?, 'REVERSAL', ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
            reversalId, order.id(), original.opNo(), original.good().negate(), original.defect().negate(), original.scrap().negate(),
            original.uom(), effectiveLotNo(original.lotNo(), order.id()), reason, reportId, operator, MDC.get("requestId"), DbTimes.ts(now),
            original.station(), original.labor().negate());
        // 工时与数量一起反冲；冲销记录沿用原报工的工位
        jdbc.update("update wo_operation_progress set good_qty = good_qty - ?, defect_qty = defect_qty - ?, scrap_qty = scrap_qty - ?, effective_reports = effective_reports - 1, labor_hours = labor_hours - ?, updated_at = ? where order_id = ? and op_no = ?",
            original.good(), original.defect(), original.scrap(), original.labor(), DbTimes.ts(now), order.id(), original.opNo());
        if (original.defect().signum() > 0)
            defectEvent(order, original.opNo(), original.uom(), effectiveLotNo(original.lotNo(), order.id()), original.defect(), reversalId, reportId, "DefectReversed", defectReversedTag);

        String status = order.status();
        List<Progress> updated = progress(order.id());
        // DONE 的任务冲销后累计不足上限 → PAUSED（PM 2026-10-14 确认：不回 RUNNING，现场不一定有人在做，由操作员显式 resume）
        int idx = indexOf(updated, original.opNo());
        OperationTaskStates.Task task = tasks.find(order.id(), original.opNo());
        if ("DONE".equals(task.status()) && updated.get(idx).total().compareTo(limit(order, updated, idx)) < 0)
            tasks.transition(task, "PAUSED", "PAUSE", "REPORT_REVERSED", operator);
        // #69：冲销使下游工序上限回到 0（上道已无合格数流入）且下游没有有效报工时，READY 的下游任务退回 PENDING（不对外发事件）
        if (idx + 1 < updated.size() && limit(order, updated, idx + 1).signum() <= 0 && updated.get(idx + 1).effective() <= 0) {
            OperationTaskStates.Task down = tasks.find(order.id(), updated.get(idx + 1).opNo());
            if ("READY".equals(down.status())) tasks.transition(down, "PENDING", "FLOW_BACK", "REPORT_REVERSED", operator);
        }
        Progress lastOp = updated.get(updated.size() - 1);
        if ("COMPLETED".equals(status) && lastOp.good().compareTo(order.planned()) < 0) {
            // 冲销导致末道合格数不足计划数：从完工退回生产中并发布 WorkOrderReopened；全部冲销后仍保持 IN_PROGRESS，不回到 RELEASED
            long v = changeStatus(order.id(), status, "IN_PROGRESS", "REPORT_REVERSED", "冲销报工 " + reportId, operator);
            var payload = new LinkedHashMap<String, Object>();
            payload.put("workOrderNo", order.id());
            payload.put("reversedReportId", reportId);
            payload.put("completedQty", num(lastOp.good()));
            payload.put("uom", lastOp.uom() != null ? lastOp.uom() : original.uom());
            payload.put("aggregateVersion", v);
            journal.routedEvent(order.id(), "WorkOrderReopened", eventTopic, reopenedTag, payload);
            status = "IN_PROGRESS";
        }
        LOG.info("冲销成功：工单 {} 报工 {} 冲销记录 {} 工单状态 {}", order.id(), reportId, reversalId, status);
        return new OperationReportResult(reportView(findReport(reversalId).orElseThrow()), progressView(order.id(), status, order.planned(), updated));
    }

    // ------------------------------------------------------------------ 手工完工

    public CommandResult complete(String orderNo, CompleteWorkOrderRequest req, String key, String operator) {
        String hash = hash("COMPLETE", orderNo, req.reason());
        return idempotent(operator, key, "COMPLETE", orderNo, hash, 200, order -> doComplete(order, req.reason(), operator));
    }

    private WorkOrderCompletionView doComplete(Order order, String reason, String operator) {
        switch (order.status()) {
            case "CLOSED" -> throw WorkOrderProblemType.WORKORDER_CLOSED.exception("工单 " + order.id() + " 已关闭，不能完工");
            case "CANCELLED" -> throw WorkOrderProblemType.WORKORDER_CANCELLED.exception("工单 " + order.id() + " 已取消，不能完工");
            case "RELEASED", "IN_PROGRESS" -> { }
            default -> throw WorkOrderProblemType.WORK_ORDER_STATE_CONFLICT.exception("工单当前状态 " + order.status() + " 不允许手工完工");
        }
        List<Progress> ops = progress(order.id());
        List<String> unreported = ops.stream().filter(p -> p.effective() <= 0).map(Progress::opNo).toList();
        if (!unreported.isEmpty())
            throw WorkOrderProblemType.OPERATIONS_UNREPORTED.exception("以下工序没有有效报工，不能手工完工：" + String.join(", ", unreported),
                Map.of("operations", unreported));
        BigDecimal lastGood = ops.get(ops.size() - 1).good();
        BigDecimal shortfall = order.planned().subtract(lastGood).max(ZERO);
        long v = changeStatus(order.id(), order.status(), "COMPLETED", "MANUAL_COMPLETE", reason, operator);
        journal.routedEvent(order.id(), "WorkOrderCompleted", eventTopic, completedTag, completedPayload(order, lastGood, ops.get(ops.size() - 1).uom(), true, reason, v));
        LOG.info("手工完工：工单 {} 计划 {} 末道合格 {} 短缺 {} 原因 {}", order.id(), num(order.planned()), num(lastGood), num(shortfall), reason);
        return new WorkOrderCompletionView(progressView(order.id(), "COMPLETED", order.planned(), ops), num(shortfall), reason);
    }

    // ------------------------------------------------------------------ 查询

    public WorkOrderProgressView progressView(String orderNo) {
        Order order = jdbc.query("select id, status, quantity, product from work_order where id = ?", (rs, i) ->
                new Order(rs.getString(1), rs.getString(2), BigDecimal.valueOf(rs.getLong(3)).setScale(SCALE), rs.getString(4)), orderNo)
            .stream().findFirst().orElseThrow(() -> WorkOrderProblemType.WORK_ORDER_NOT_FOUND.exception("工单不存在：" + orderNo));
        List<Progress> ops = readProgress(order.id());
        if (ops.isEmpty()) ops = fromTasks(order.id());
        return progressView(order.id(), order.status(), order.planned(), ops);
    }

    /**
     * 取消工单（幂等）：与报工共用“锁工单行 → 查幂等记录 → 执行 → 保存幂等记录”的单事务流程，因此与报工、冲销、完工串行。
     * 同键重放原样返回首次结果（不会再次登记释放）；换键再取消已取消的工单返回 409 workorder-cancelled。
     * 状态变更与预留释放任务在同一事务内提交，释放由 ReservationReleaseWorker 异步完成。
     */
    public CommandResult cancel(String orderNo, CancelWorkOrderRequest req, String key, String operator) {
        String note = req == null ? null : req.note();
        String hash = hash("CANCEL", orderNo, note);
        return idempotent(operator, key, "CANCEL", orderNo, hash, 200, order -> cancellation.cancelInTx(order.id(), order.status(), note, operator));
    }

    public List<OperationReportView> reports(String orderNo) {
        progressView(orderNo); // 校验工单存在
        return jdbc.query("select * from wo_operation_report where order_id = ? order by created_at, report_id", (rs, i) -> reportView(mapReport(rs)), orderNo);
    }

    // ------------------------------------------------------------------ 关闭 / 撤回下达（PR2）

    /**
     * 关闭工单：COMPLETED → CLOSED。先在事务外查询质量服务（不在持有工单行锁时做远程调用），存在未结案 NCR 409 open-ncr-exists，
     * 质量服务不可用 503 quality-service-unavailable。同键重放直接返回首次结果，不再查询质量服务。
     * 查询与加锁之间新建的 NCR 不在本次校验范围内（NCR 只能针对已有报工，COMPLETED 之后的新 NCR 由质量流程处理）。
     */
    public CommandResult close(String orderNo, CloseWorkOrderRequest req, String key, String operator) {
        String note = req == null ? null : req.note();
        String hash = hash("CLOSE", orderNo, note);
        Optional<CommandResult> prior = replay(operator, key, hash);
        if (prior.isPresent()) return prior.get();
        closure.requireNoOpenNcr(orderNo);
        return idempotent(operator, key, "CLOSE", orderNo, hash, 200, order -> closure.closeInTx(order.id(), order.status(), note, operator));
    }

    /**
     * 撤回下达：RELEASED → CREATED（无报工、无开工任务）。锁序与冻结 / 恢复一致：先锁 work_order_lifecycle 行，再锁 work_order 行，
     * 避免与冻结（先改生命周期再改工单）形成相反锁序而死锁。
     */
    public CommandResult withdrawRelease(String orderNo, WithdrawReleaseRequest req, String key, String operator) {
        String note = req == null ? null : req.note();
        String hash = hash("WITHDRAW_RELEASE", orderNo, note);
        return tx.execute(s -> {
            jdbc.query("select order_id from work_order_lifecycle where order_id = ? for update", (rs, i) -> rs.getString(1), orderNo);
            return idempotent(operator, key, "WITHDRAW_RELEASE", orderNo, hash, 200, order -> closure.withdrawInTx(order.id(), order.status(), note, operator));
        });
    }

    // ------------------------------------------------------------------ 内部

    /** 在单个事务内：锁工单行 → 查幂等记录（命中则重放）→ 执行命令 → 保存幂等记录。 */
    CommandResult idempotent(String principal, String key, String command, String orderNo, String hash, int status,
                                     java.util.function.Function<Order, Object> work) {
        try {
            return tx.execute(s -> {
                Order order = metrics.timeLock(() -> lock(orderNo));
                Optional<CommandResult> prior = replay(principal, key, hash);
                if (prior.isPresent()) return prior.get();
                Object view = work.apply(order);
                String body = json(ApiResponse.ok(view));
                jdbc.update("insert into wo_command_request (principal, idempotency_key, command, order_id, request_hash, response_status, response_body, created_at) values (?, ?, ?, ?, ?, ?, ?, ?)",
                    principal, key, command, orderNo, hash, status, body, DbTimes.ts(clock.instant()));
                return new CommandResult(status, body, false);
            });
        } catch (DuplicateKeyException e) {
            // 同一键被并发用于另一张工单（不同工单的锁互不阻塞）：先提交者胜，后到者按其记录重放或判冲突
            LOG.info("幂等键 {} 并发提交，按已提交记录处理", key);
            return replay(principal, key, hash).orElseThrow(() -> e);
        }
    }

    private Optional<CommandResult> replay(String principal, String key, String hash) {
        return jdbc.query("select request_hash, response_status, response_body from wo_command_request where principal = ? and idempotency_key = ?",
            (rs, i) -> {
                if (!rs.getString(1).equals(hash))
                    throw WorkOrderProblemType.IDEMPOTENCY_KEY_CONFLICT.exception("Idempotency-Key 已用于不同的请求");
                return new CommandResult(rs.getInt(2), rs.getString(3), true);
            }, principal, key).stream().findFirst();
    }

    private Order lock(String orderNo) {
        return jdbc.query("select id, status, quantity, product from work_order where id = ? for update", (rs, i) ->
                new Order(rs.getString(1), rs.getString(2), BigDecimal.valueOf(rs.getLong(3)).setScale(SCALE), rs.getString(4)), orderNo)
            .stream().findFirst().orElseThrow(() -> WorkOrderProblemType.WORK_ORDER_NOT_FOUND.exception("工单不存在：" + orderNo));
    }

    /** 读取工序累计；首次访问时按下达时的工序任务（工艺快照）补齐累计行（工单行已加锁，补齐不会并发重复）。 */
    private List<Progress> progress(String orderId) {
        jdbc.update("insert into wo_operation_progress (order_id, op_no, seq, good_qty, defect_qty, scrap_qty, uom, effective_reports, updated_at) "
            + "select t.order_id, t.op_no, t.seq, 0, 0, 0, null, 0, ? from operation_task t where t.order_id = ? "
            + "and not exists (select 1 from wo_operation_progress p where p.order_id = t.order_id and p.op_no = t.op_no)",
            DbTimes.ts(clock.instant()), orderId);
        List<Progress> ops = readProgress(orderId);
        if (ops.isEmpty())
            throw WorkOrderProblemType.WORK_ORDER_STATE_CONFLICT.exception("工单 " + orderId + " 没有下达时的工序快照");
        return ops;
    }

    private List<Progress> readProgress(String orderId) {
        return jdbc.query("select op_no, seq, good_qty, defect_qty, scrap_qty, uom, effective_reports, labor_hours from wo_operation_progress where order_id = ? order by seq",
            (rs, i) -> new Progress(rs.getString(1), rs.getInt(2), qty(rs.getBigDecimal(3)), qty(rs.getBigDecimal(4)), qty(rs.getBigDecimal(5)), rs.getString(6), rs.getInt(7),
                rs.getBigDecimal(8).setScale(2, RoundingMode.UNNECESSARY)), orderId);
    }

    private List<Progress> fromTasks(String orderId) {
        return jdbc.query("select op_no, seq from operation_task where order_id = ? order by seq",
            (rs, i) -> new Progress(rs.getString(1), rs.getInt(2), ZERO, ZERO, ZERO, null, 0, HOURS_ZERO), orderId);
    }

    private static int indexOf(List<Progress> ops, String opNo) {
        for (int i = 0; i < ops.size(); i++) if (ops.get(i).opNo().equals(opNo)) return i;
        throw WorkOrderProblemType.OPERATION_NOT_FOUND.exception("工序 " + opNo + " 不在下达时的工艺快照中");
    }

    /** 各工序累计与上限，供任务状态结算。 */
    private static List<OperationTaskStates.OpTotals> totals(Order order, List<Progress> ops) {
        List<OperationTaskStates.OpTotals> list = new ArrayList<>();
        for (int i = 0; i < ops.size(); i++) list.add(new OperationTaskStates.OpTotals(ops.get(i).opNo(), ops.get(i).total(), limit(order, ops, i)));
        return list;
    }

    /**
     * 解析工时：入口 @Pattern 已保证非负、最多 2 位小数；这里再防御一次（如内部直接调用），不合法返回 validation-error。
     */
    static BigDecimal hours(String v) {
        if (v == null || !v.matches("\\d{1,6}(\\.\\d{1,2})?"))
            throw WorkOrderProblemType.VALIDATION_ERROR.exception("laborHours 必须是非负、最多 2 位小数的十进制字符串");
        return new BigDecimal(v).setScale(2, RoundingMode.UNNECESSARY);
    }

    /** 本工序上限：首道 = 计划数；后续 = 上道累计合格数。 */
    private static BigDecimal limit(Order order, List<Progress> ops, int k) { return k == 0 ? order.planned() : ops.get(k - 1).good(); }

    private WorkOrderProgressView progressView(String orderId, String status, BigDecimal planned, List<Progress> ops) {
        List<OperationProgressView> views = new ArrayList<>();
        // 附带派工信息（M1）：任务状态、工位、操作工、计划时间与任务版本
        Map<String, OperationTaskStates.Task> byOp = new HashMap<>();
        for (OperationTaskStates.Task t : tasks.all(orderId)) byOp.put(t.opNo(), t);
        for (int i = 0; i < ops.size(); i++) {
            Progress p = ops.get(i);
            BigDecimal limit = i == 0 ? planned : ops.get(i - 1).good();
            OperationTaskStates.Task t = byOp.get(p.opNo());
            views.add(new OperationProgressView(p.opNo(), p.seq(), num(p.good()), num(p.defect()), num(p.scrap()), p.uom(), num(limit), p.effective(),
                p.labor().toPlainString(), t == null ? null : t.status(), t == null ? null : t.station(), t == null ? null : t.operator(),
                t == null ? null : t.plannedStart(), t == null ? null : t.plannedEnd(), t == null ? 0 : t.version(),
                t == null ? null : t.name(), t == null ? null : t.workCenter()));
        }
        return new WorkOrderProgressView(orderId, status, num(planned), views);
    }

    /** 条件更新状态并递增聚合版本，写审计；返回变更后的版本号（随状态事件发布为 aggregateVersion）。 */
    private long changeStatus(String orderId, String from, String to, String reason, String note, String operator) {
        int n = jdbc.update("update work_order set status = ?, version = version + 1 where id = ? and status = ?", to, orderId, from);
        if (n != 1) throw new IllegalStateException("工单状态更新失败：" + orderId + " " + from + " -> " + to);
        journal.statusChanged(orderId, from, to, reason, note, operator);
        Long v = jdbc.queryForObject("select version from work_order where id = ?", Long.class, orderId);
        return v == null ? 0 : v;
    }

    /**
     * 计算生效批次号：请求带了 lotNo 就用请求值，否则缺省为工单号。
     * 冲销时也经过此方法，兼容本规则上线前 lot_no 为空的历史报工，保证 DefectReversed 与 DefectReported 批次一致。
     */
    static String effectiveLotNo(String lotNo, String workOrderNo) {
        return lotNo != null ? lotNo : workOrderNo;
    }

    private void defectEvent(Order order, String opNo, String uom, String lotNo, BigDecimal defect, String reportId,
                             String reversedReportId, String type, String tag) {
        var payload = new LinkedHashMap<String, Object>();
        payload.put("workOrderNo", order.id());
        payload.put("operationNo", opNo);
        payload.put("materialCode", order.product());
        payload.put("lotNo", lotNo);
        payload.put("uom", uom);
        payload.put("defectQty", num(defect));
        if (reversedReportId == null) {
            payload.put("reportId", reportId);
        } else {
            // DefectReversed 不带 reportId：reversedReportId = 被冲销的原报工，reversalId = 本次冲销记录
            payload.put("reversedReportId", reversedReportId);
            payload.put("reversalId", reportId);
        }
        journal.routedEvent(order.id(), type, qualityTopic, tag, payload);
    }

    /**
     * WorkOrderCompleted 字段（群内约定）：eventId、workOrderNo、uom、occurredAt、completionMode（AUTO|MANUAL）、
     * plannedQty、completedQty、shortfallQty（十进制字符串；AUTO 固定为 "0"）、reason（仅 MANUAL）、aggregateVersion。completedQty 恒为末道工序累计合格数，plannedQty = completedQty + shortfallQty。
     */
    private static Map<String, Object> completedPayload(Order order, BigDecimal lastGood, String uom, boolean manual, String reason, long version) {
        var payload = new LinkedHashMap<String, Object>();
        payload.put("workOrderNo", order.id());
        payload.put("uom", uom);
        payload.put("completionMode", manual ? "MANUAL" : "AUTO");
        payload.put("plannedQty", num(order.planned()));
        payload.put("completedQty", num(lastGood));
        payload.put("shortfallQty", manual ? num(order.planned().subtract(lastGood).max(ZERO)) : "0");
        if (manual) payload.put("reason", reason);
        payload.put("aggregateVersion", version);
        return payload;
    }

    private static Map<String, Object> overData(String opNo, BigDecimal limit, BigDecimal reported, BigDecimal requested) {
        return Map.of("operationNo", opNo, "limit", num(limit), "reported", num(reported), "requested", num(requested),
            "remaining", num(limit.subtract(reported).max(ZERO)));
    }

    private Optional<Report> findReport(String reportId) {
        return jdbc.query("select * from wo_operation_report where report_id = ?", (rs, i) -> mapReport(rs), reportId).stream().findFirst();
    }

    private static Report mapReport(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new Report(rs.getString("report_id"), rs.getString("order_id"), rs.getString("type"), rs.getString("op_no"),
            qty(rs.getBigDecimal("good_qty")), qty(rs.getBigDecimal("defect_qty")), qty(rs.getBigDecimal("scrap_qty")),
            rs.getString("uom"), rs.getString("lot_no"), rs.getString("note"), rs.getString("reversed_report_id"),
            rs.getString("operator"), rs.getTimestamp("created_at").toInstant(), rs.getString("station_code"),
            rs.getBigDecimal("labor_hours").setScale(2, RoundingMode.UNNECESSARY));
    }

    private static OperationReportView reportView(Report r) {
        return new OperationReportView(r.reportId(), r.type(), r.opNo(), num(r.good()), num(r.defect()), num(r.scrap()), r.uom(),
            r.lotNo(), r.note(), r.reversedReportId(), r.operator(), r.createdAt(), r.station(), r.labor().toPlainString());
    }

    /** 统一为 6 位小数；超过 6 位的输入已被 @Digits 拒绝，这里不会发生舍入。 */
    static BigDecimal qty(BigDecimal v) { return v.setScale(SCALE, RoundingMode.UNNECESSARY); }
    static String num(BigDecimal v) { return v == null ? null : v.setScale(SCALE, RoundingMode.UNNECESSARY).toPlainString(); }

    private String json(Object o) {
        try { return mapper.writeValueAsString(o); } catch (JsonProcessingException e) { throw new IllegalStateException("响应序列化失败", e); }
    }

    static String hash(String... parts) {
        try {
            MessageDigest d = MessageDigest.getInstance("SHA-256");
            for (String p : parts) { d.update(String.valueOf(p).getBytes(StandardCharsets.UTF_8)); d.update((byte) 0); }
            return HexFormat.of().formatHex(d.digest());
        } catch (NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
    }
}
