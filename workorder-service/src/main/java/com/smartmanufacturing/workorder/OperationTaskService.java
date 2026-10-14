package com.smartmanufacturing.workorder;

import com.smartmanufacturing.api.masterdata.Station;
import com.smartmanufacturing.api.masterdata.WorkCenter;
import com.smartmanufacturing.api.workorder.DispatchOperationRequest;
import com.smartmanufacturing.api.workorder.DispatchTaskView;
import com.smartmanufacturing.api.workorder.TaskActionRequest;
import com.smartmanufacturing.common.BusinessException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import java.util.*;

/**
 * 工序派工、改派、取消派工、开工、暂停、恢复（设计 docs/workorder-dispatch-design.md §3–§4）。
 * <p>每个写命令复用报工的单事务流程 {@link OperationReportingService#idempotent}：锁工单行 → 幂等记录 → 执行 → 保存幂等记录，
 * 因此与报工、冲销、取消串行；Idempotency-Key 必填，重放返回首次响应。
 * <p>工位归属校验需要调用主数据服务，放在事务之外先做（不持锁做远程调用）；工位是否属于工作中心不随事务变化。
 */
@Service
public class OperationTaskService {
    private static final Logger LOG = LoggerFactory.getLogger(OperationTaskService.class);
    /** 任务列表允许的状态取值。 */
    static final Set<String> STATUSES = Set.of("PENDING", "READY", "RUNNING", "PAUSED", "DONE", "CANCELLED");

    private final OperationReportingService reporting;
    private final OperationTaskStates tasks;
    private final MasterdataClient masterdata;
    private final JdbcTemplate jdbc;

    public OperationTaskService(OperationReportingService reporting, OperationTaskStates tasks, MasterdataClient masterdata, JdbcTemplate jdbc) {
        this.reporting = reporting; this.tasks = tasks; this.masterdata = masterdata; this.jdbc = jdbc;
    }

    // ------------------------------------------------------------------ 派工类（execution:dispatch）

    /** 派工：未派工且 PENDING / READY（老数据补派工也允许 RUNNING / PAUSED）；写入工位、操作工、计划时间，状态不变。 */
    public OperationReportingService.CommandResult dispatch(String orderNo, String opNo, DispatchOperationRequest req, String key, String actor) {
        checkPlan(req);
        checkStation(orderNo, opNo, req.stationCode());
        String hash = OperationReportingService.hash("DISPATCH", orderNo, opNo, req.stationCode(), req.operator(),
            String.valueOf(req.plannedStart()), String.valueOf(req.plannedEnd()), req.note());
        return reporting.idempotent(actor, key, "DISPATCH", orderNo, hash, 200, order -> {
            requireActive(order, "派工");
            var t = tasks.find(order.id(), opNo);
            if (t.dispatched())
                throw conflict(t, "工序已派工到工位 " + t.station() + "，请使用改派");
            // 未派工的老数据可能已被报工自动开工（RUNNING）或冲销后暂停（PAUSED），允许补派工，否则无法再显式暂停 / 恢复
            if (!Set.of("PENDING", "READY", "RUNNING", "PAUSED").contains(t.status())) throw conflict(t, "当前任务状态不允许派工");
            var after = tasks.saveDispatch(t, "DISPATCH", req.stationCode(), req.operator(), req.plannedStart(), req.plannedEnd(), req.note(), actor);
            LOG.info("派工：工单 {} 工序 {} 工位 {} 操作工 {}", order.id(), opNo, req.stationCode(), req.operator());
            return after.view();
        });
    }

    /**
     * 改派：READY / PAUSED 且已派工（已有报工也可以）；RUNNING 时 409 task-state-conflict（需先暂停）。
     * expectedVersion 必填且必须等于当前任务版本，否则 409 concurrent-modification；已有报工记录的工位与操作人不变。
     */
    public OperationReportingService.CommandResult reassign(String orderNo, String opNo, DispatchOperationRequest req, String key, String actor) {
        if (req.expectedVersion() == null) throw WorkOrderProblemType.VALIDATION_ERROR.exception("改派必须提供 expectedVersion");
        checkPlan(req);
        checkStation(orderNo, opNo, req.stationCode());
        String hash = OperationReportingService.hash("REASSIGN", orderNo, opNo, req.stationCode(), req.operator(),
            String.valueOf(req.plannedStart()), String.valueOf(req.plannedEnd()), req.note(), String.valueOf(req.expectedVersion()));
        return reporting.idempotent(actor, key, "REASSIGN", orderNo, hash, 200, order -> {
            requireActive(order, "改派");
            var t = tasks.find(order.id(), opNo);
            if (!t.dispatched()) throw WorkOrderProblemType.TASK_NOT_DISPATCHED.exception("工序 " + opNo + " 尚未派工，请先派工");
            if (!Set.of("READY", "PAUSED").contains(t.status()))
                throw conflict(t, "RUNNING".equals(t.status()) ? "任务正在进行中，请先暂停再改派" : "当前任务状态不允许改派");
            if (t.version() != req.expectedVersion())
                throw WorkOrderProblemType.CONCURRENT_MODIFICATION.exception("任务版本已变化（当前 " + t.version() + "），请刷新后重试",
                    Map.of("currentVersion", t.version()));
            var after = tasks.saveDispatch(t, "REASSIGN", req.stationCode(), req.operator(), req.plannedStart(), req.plannedEnd(), req.note(), actor);
            LOG.info("改派：工单 {} 工序 {} 工位 {} → {}", order.id(), opNo, t.station(), req.stationCode());
            return after.view();
        });
    }

    /** 取消派工：已派工、PENDING / READY、本道没有任何报工；reason 必填。 */
    public OperationReportingService.CommandResult cancelDispatch(String orderNo, String opNo, TaskActionRequest req, String key, String actor) {
        String reason = requireReason(req, "取消派工");
        String hash = OperationReportingService.hash("CANCEL_DISPATCH", orderNo, opNo, reason);
        return reporting.idempotent(actor, key, "CANCEL_DISPATCH", orderNo, hash, 200, order -> {
            requireActive(order, "取消派工");
            var t = tasks.find(order.id(), opNo);
            if (!t.dispatched()) throw WorkOrderProblemType.TASK_NOT_DISPATCHED.exception("工序 " + opNo + " 没有派过工");
            if (!Set.of("PENDING", "READY").contains(t.status())) throw conflict(t, "当前任务状态不允许取消派工");
            Integer reports = jdbc.queryForObject("select count(*) from wo_operation_report where order_id = ? and op_no = ?", Integer.class, order.id(), opNo);
            if (reports != null && reports > 0) throw conflict(t, "工序已有报工，不能取消派工，请改派");
            return tasks.saveDispatch(t, "CANCEL_DISPATCH", null, null, null, null, reason, actor).view();
        });
    }

    // ------------------------------------------------------------------ 执行类（execution:report）

    /** 开工：READY 且已派工 → RUNNING。M1 不限制只能由指派操作工开工。 */
    public OperationReportingService.CommandResult start(String orderNo, String opNo, TaskActionRequest req, String key, String actor) {
        return move(orderNo, opNo, req, key, actor, "START", "READY", "RUNNING", false);
    }

    /** 暂停：RUNNING → PAUSED，reason 必填。 */
    public OperationReportingService.CommandResult pause(String orderNo, String opNo, TaskActionRequest req, String key, String actor) {
        requireReason(req, "暂停");
        return move(orderNo, opNo, req, key, actor, "PAUSE", "RUNNING", "PAUSED", true);
    }

    /** 恢复：PAUSED → RUNNING；工单 ON_HOLD 时 409 workorder-state-conflict。 */
    public OperationReportingService.CommandResult resume(String orderNo, String opNo, TaskActionRequest req, String key, String actor) {
        return move(orderNo, opNo, req, key, actor, "RESUME", "PAUSED", "RUNNING", false);
    }

    private OperationReportingService.CommandResult move(String orderNo, String opNo, TaskActionRequest req, String key, String actor,
                                                         String action, String from, String to, boolean reasonRequired) {
        String reason = req == null ? null : req.reason();
        String hash = OperationReportingService.hash(action, orderNo, opNo, reason);
        return reporting.idempotent(actor, key, action, orderNo, hash, 200, order -> {
            requireActive(order, action);
            var t = tasks.find(order.id(), opNo);
            // 显式开工 / 暂停 / 恢复要求先派工（报工不要求，见设计 §3）
            if (!t.dispatched()) throw WorkOrderProblemType.TASK_NOT_DISPATCHED.exception("工序 " + opNo + " 尚未派工，不能" + label(action));
            if (!from.equals(t.status())) throw conflict(t, "当前任务状态不允许" + label(action));
            var after = tasks.transition(t, to, action, reason, actor);
            LOG.info("任务{}：工单 {} 工序 {} {} → {}", label(action), order.id(), opNo, from, to);
            return after.view();
        });
    }

    // ------------------------------------------------------------------ 查询

    /** 工位工作台任务列表：按计划开始（空值排后）、工单号、工序顺序排序；size 1–200。 */
    public List<DispatchTaskView> list(String station, List<String> statuses, String operator, int page, int size) {
        if (page < 0 || size < 1 || size > 200)
            throw WorkOrderProblemType.VALIDATION_ERROR.exception("分页参数非法：page ≥ 0，1 ≤ size ≤ 200");
        StringBuilder sql = new StringBuilder("select " + OperationTaskStates.COLUMNS + " from operation_task where 1 = 1");
        List<Object> args = new ArrayList<>();
        if (station != null && !station.isBlank()) { sql.append(" and station_code = ?"); args.add(station); }
        if (operator != null && !operator.isBlank()) { sql.append(" and operator = ?"); args.add(operator); }
        if (statuses != null && !statuses.isEmpty()) {
            for (String s : statuses)
                if (!STATUSES.contains(s)) throw WorkOrderProblemType.VALIDATION_ERROR.exception("未知的任务状态：" + s);
            sql.append(" and status in (").append(String.join(", ", Collections.nCopies(statuses.size(), "?"))).append(")");
            args.addAll(statuses);
        }
        sql.append(" order by case when planned_start is null then 1 else 0 end, planned_start, order_id, seq limit ? offset ?");
        args.add(size);
        args.add(page * size);
        return jdbc.query(sql.toString(), OperationTaskStates.MAPPER, args.toArray()).stream().map(OperationTaskStates.Task::view).toList();
    }

    // ------------------------------------------------------------------ 内部

    /** 派工类与执行类命令只允许 RELEASED / IN_PROGRESS 的工单；CREATED 沿用 404 workorder-not-released。 */
    private static void requireActive(OperationReportingService.Order order, String what) {
        switch (order.status()) {
            case "RELEASED", "IN_PROGRESS" -> { }
            case "CREATED" -> throw WorkOrderProblemType.WORK_ORDER_NOT_RELEASED.exception("工单 " + order.id() + " 尚未下达");
            case "CANCELLED" -> throw WorkOrderProblemType.WORKORDER_CANCELLED.exception("工单 " + order.id() + " 已取消，不能" + what);
            case "CLOSED" -> throw WorkOrderProblemType.WORKORDER_CLOSED.exception("工单 " + order.id() + " 已关闭，不能" + what);
            default -> throw WorkOrderProblemType.WORK_ORDER_STATE_CONFLICT.exception("工单当前状态 " + order.status() + " 不允许" + what);
        }
    }

    private static BusinessException conflict(OperationTaskStates.Task t, String detail) {
        return WorkOrderProblemType.TASK_STATE_CONFLICT.exception("工序 " + t.opNo() + "（" + t.status() + "）：" + detail,
            Map.of("operationNo", t.opNo(), "taskStatus", t.status()));
    }

    private static String requireReason(TaskActionRequest req, String what) {
        if (req == null || req.reason() == null || req.reason().isBlank())
            throw WorkOrderProblemType.VALIDATION_ERROR.exception(what + "必须填写 reason");
        return req.reason();
    }

    private static void checkPlan(DispatchOperationRequest req) {
        if (req.plannedStart() != null && req.plannedEnd() != null && !req.plannedEnd().isAfter(req.plannedStart()))
            throw WorkOrderProblemType.VALIDATION_ERROR.exception("plannedEnd 必须晚于 plannedStart");
    }

    /**
     * 工位必须存在且属于该工序的工作中心，否则 422 station-not-found。工作中心在主数据中不存在（404/422）同样视为工位不存在；
     * 主数据不可用时 503 masterdata-service-unavailable，不放行。任务不存在时跳过，由事务内的检查返回准确的 404。
     */
    private void checkStation(String orderNo, String opNo, String stationCode) {
        Optional<OperationTaskStates.Task> task = tasks.findOptional(orderNo, opNo);
        if (task.isEmpty()) return;
        String wc = task.get().workCenter();
        WorkCenter center;
        try {
            var resp = masterdata.workCenter(wc);
            center = resp == null ? null : resp.data();
        } catch (BusinessException | feign.FeignException e) {
            // 未启用 Sentinel 降级时 Feign 异常原样抛出，这里按降级工厂同一规则映射
            BusinessException mapped = e instanceof BusinessException b ? b : MasterdataFallbackFactory.map(e);
            if (!WorkOrderProblemType.RELEASE_MASTERDATA_INVALID.code().equals(mapped.code())) throw mapped;
            center = null;
        }
        boolean ok = center != null && center.stations() != null
            && center.stations().stream().map(Station::code).anyMatch(stationCode::equals);
        if (!ok) throw WorkOrderProblemType.STATION_NOT_FOUND.exception("工位 " + stationCode + " 不存在或不属于工作中心 " + wc,
            Map.of("stationCode", stationCode, "workCenterCode", wc));
    }

    private static String label(String action) {
        return switch (action) { case "START" -> "开工"; case "PAUSE" -> "暂停"; case "RESUME" -> "恢复"; default -> action; };
    }
}
