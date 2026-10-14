package com.smartmanufacturing.workorder;

import com.smartmanufacturing.api.workorder.DispatchTaskView;
import org.slf4j.MDC;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Component;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.util.*;

/**
 * 工序任务状态与派工信息的持久化、日志与事件（设计 docs/workorder-dispatch-design.md §2–§3、§8）。
 * <p>所有方法都必须在“已锁定 work_order 行”的调用方事务内执行（派工命令、报工、冲销、冻结、取消），
 * 锁序统一为 work_order → operation_task，避免死锁；任务行另以 version 条件更新兜底，失败返回 409 concurrent-modification。
 * <p>状态变化都写 operation_task_log 并经发件箱发布 Operation* 事件（topic workorder-event，Tag 取自 Nacos manufacturing.mq.tags）。
 */
@Component
class OperationTaskStates {
    /** 任务行快照；stationCode 为空表示未派工（兼容已下达的老数据）。 */
    record Task(String orderId, String opNo, int seq, String name, String workCenter, String status, String station,
                String operator, Instant plannedStart, Instant plannedEnd, Instant dispatchedAt, String dispatchedBy, long version) {
        boolean dispatched() { return station != null; }
        DispatchTaskView view() {
            return new DispatchTaskView(orderId, opNo, name, workCenter, status, station, operator, plannedStart, plannedEnd, dispatchedAt, dispatchedBy, version);
        }
    }

    /** 报工 / 冲销后传入的工序累计：total = 合格 + 不良 + 报废，limit 为本道当前上限。 */
    record OpTotals(String opNo, BigDecimal total, BigDecimal limit) {}

    static final String COLUMNS = "order_id, op_no, seq, name, work_center_code, status, station_code, operator, planned_start, planned_end, dispatched_at, dispatched_by, version";
    static final RowMapper<Task> MAPPER = (rs, i) -> new Task(rs.getString(1), rs.getString(2), rs.getInt(3), rs.getString(4), rs.getString(5),
        rs.getString(6), rs.getString(7), rs.getString(8), instant(rs.getTimestamp(9)), instant(rs.getTimestamp(10)),
        instant(rs.getTimestamp(11)), rs.getString(12), rs.getLong(13));

    private final JdbcTemplate jdbc;
    private final WorkOrderJournal journal;
    private final Clock clock;
    private final MqProperties mq;
    private final EquipmentReservationPort equipment;

    OperationTaskStates(JdbcTemplate jdbc, WorkOrderJournal journal, Clock clock, MqProperties mq, EquipmentReservationPort equipment) {
        this.jdbc = jdbc; this.journal = journal; this.clock = clock; this.mq = mq; this.equipment = equipment;
        // 启动即解析全部任务事件 Tag，缺配置直接启动失败
        for (String k : List.of("operation-dispatched", "operation-dispatch-cancelled", "operation-started", "operation-paused",
            "operation-resumed", "operation-completed", "operation-cancelled")) mq.tag(k);
    }

    private static Instant instant(java.sql.Timestamp t) { return t == null ? null : t.toInstant(); }

    /** 读取单个任务；不存在返回 404 operation-not-found。 */
    Task find(String orderId, String opNo) {
        return findOptional(orderId, opNo)
            .orElseThrow(() -> WorkOrderProblemType.OPERATION_NOT_FOUND.exception("工序 " + opNo + " 不在下达时的工艺快照中"));
    }

    Optional<Task> findOptional(String orderId, String opNo) {
        return jdbc.query("select " + COLUMNS + " from operation_task where order_id = ? and op_no = ?", MAPPER, orderId, opNo).stream().findFirst();
    }

    /** 本工单全部任务，按工序顺序。 */
    List<Task> all(String orderId) {
        return jdbc.query("select " + COLUMNS + " from operation_task where order_id = ? order by seq", MAPPER, orderId);
    }

    /**
     * 写入派工信息（派工、改派、取消派工），任务状态不变；以 version 条件更新，版本已变时 409 concurrent-modification。
     * station 为 null 表示取消派工（同时清空操作工与计划时间）。
     */
    Task saveDispatch(Task t, String action, String station, String operator, Instant start, Instant end, String reason, String actor) {
        Instant now = clock.instant();
        int n = jdbc.update("update operation_task set station_code = ?, operator = ?, planned_start = ?, planned_end = ?, dispatched_at = ?, dispatched_by = ?, version = version + 1 where order_id = ? and op_no = ? and version = ?",
            station, operator, ts(start), ts(end), station == null ? null : DbTimes.ts(now), station == null ? null : actor, t.orderId(), t.opNo(), t.version());
        if (n != 1) throw WorkOrderProblemType.CONCURRENT_MODIFICATION.exception("工序任务已被并发修改，请刷新后重试");
        log(t, action, t.status(), t.status(), station, operator, reason, actor);
        Task after = find(t.orderId(), t.opNo());
        var extra = new LinkedHashMap<String, Object>();
        if (station == null) {
            extra.put("previousStationCode", t.station());
            extra.put("reason", reason);
            event(after, "OperationDispatchCancelled", "operation-dispatch-cancelled", extra);
        } else {
            // 改派时带上原工位，便于看板把任务从旧工位移走
            extra.put("previousStationCode", t.station());
            extra.put("plannedStart", start == null ? null : start.toString());
            extra.put("plannedEnd", end == null ? null : end.toString());
            event(after, "OperationDispatched", "operation-dispatched", extra);
        }
        return after;
    }

    /**
     * 状态迁移：条件更新状态并递增版本，写日志与事件；开工记录首次开工时间，暂停记录暂停时间。
     * 设备预留占位在这里统一调用：进入 RUNNING 时 reserve，离开 RUNNING 时 release。
     */
    Task transition(Task t, String to, String action, String reason, String actor) {
        Instant now = clock.instant();
        int n = jdbc.update("update operation_task set status = ?, started_at = case when ? = 'RUNNING' and started_at is null then ? else started_at end, "
                + "paused_at = case when ? = 'PAUSED' then ? else paused_at end, version = version + 1 where order_id = ? and op_no = ? and version = ?",
            to, to, DbTimes.ts(now), to, DbTimes.ts(now), t.orderId(), t.opNo(), t.version());
        if (n != 1) throw WorkOrderProblemType.CONCURRENT_MODIFICATION.exception("工序任务已被并发修改，请刷新后重试");
        String key = "task-" + t.orderId() + "-" + t.opNo();
        if ("RUNNING".equals(to)) equipment.reserve(t.orderId(), t.opNo(), t.station(), key);
        else if ("RUNNING".equals(t.status())) equipment.release(t.orderId(), t.opNo(), t.station(), key);
        log(t, action, t.status(), to, t.station(), t.operator(), reason, actor);
        Task after = find(t.orderId(), t.opNo());
        var extra = new LinkedHashMap<String, Object>();
        extra.put("previousStatus", t.status());
        if (reason != null) extra.put("reason", reason);
        switch (to) {
            case "RUNNING" -> {
                if ("PAUSED".equals(t.status())) event(after, "OperationResumed", "operation-resumed", extra);
                else event(after, "OperationStarted", "operation-started", extra);
            }
            case "PAUSED" -> event(after, "OperationPaused", "operation-paused", extra);
            case "DONE" -> event(after, "OperationCompleted", "operation-completed", extra);
            case "CANCELLED" -> event(after, "OperationCancelled", "operation-cancelled", extra);
            default -> { } // PENDING → READY 只是物料流入，不对外发事件
        }
        return after;
    }

    /**
     * 报工 / 冲销后按累计结算任务状态（按工序顺序逐道处理，使完工可以向下游级联）：
     * PENDING 且本道上限 > 0（上道已有合格数流入）→ READY；RUNNING 且累计达到上限、上道已 DONE（首道视为已满足）→ DONE。
     */
    void settle(String orderId, List<OpTotals> totals, String actor) {
        Map<String, OpTotals> byOp = new HashMap<>();
        for (OpTotals o : totals) byOp.put(o.opNo(), o);
        boolean upstreamDone = true;
        for (Task t : all(orderId)) {
            OpTotals o = byOp.get(t.opNo());
            if (o != null) {
                if ("PENDING".equals(t.status()) && o.limit().signum() > 0)
                    t = transition(t, "READY", "FLOW_IN", null, actor);
                if ("RUNNING".equals(t.status()) && upstreamDone && o.limit().signum() > 0 && o.total().compareTo(o.limit()) >= 0)
                    t = transition(t, "DONE", "DONE", null, actor);
            }
            upstreamDone = "DONE".equals(t.status());
        }
    }

    /** 工单冻结：所有 RUNNING 任务 → PAUSED（reason=WORK_ORDER_HOLD）；恢复工单后由操作工逐个 resume。 */
    void pauseAllRunning(String orderId, String actor) {
        for (Task t : all(orderId))
            if ("RUNNING".equals(t.status())) transition(t, "PAUSED", "PAUSE", "WORK_ORDER_HOLD", actor);
    }

    /** 工单取消：所有未 DONE 的任务 → CANCELLED。 */
    void cancelAll(String orderId, String actor) {
        for (Task t : all(orderId))
            if (!"DONE".equals(t.status()) && !"CANCELLED".equals(t.status())) transition(t, "CANCELLED", "CANCEL", "WORK_ORDER_CANCELLED", actor);
    }

    private void log(Task t, String action, String from, String to, String station, String operator, String reason, String actor) {
        jdbc.update("insert into operation_task_log (id, order_id, op_no, action, from_status, to_status, station_code, operator, reason, actor, request_id, created_at) values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
            UUID.randomUUID().toString(), t.orderId(), t.opNo(), action, from, to, station, operator, reason, actor, MDC.get("requestId"), DbTimes.ts(clock.instant()));
    }

    /** 任务事件公共字段：workOrderNo、operationNo、stationCode、operator、status、taskVersion、aggregateVersion（eventId / occurredAt 由 journal 补充）。 */
    private void event(Task t, String type, String tagKey, Map<String, Object> extra) {
        Long aggregate = jdbc.queryForObject("select version from work_order where id = ?", Long.class, t.orderId());
        var payload = new LinkedHashMap<String, Object>();
        payload.put("workOrderNo", t.orderId());
        payload.put("operationNo", t.opNo());
        payload.put("stationCode", t.station());
        payload.put("operator", t.operator());
        payload.put("status", t.status());
        payload.put("taskVersion", t.version());
        payload.putAll(extra);
        payload.put("aggregateVersion", aggregate == null ? 0 : aggregate);
        journal.routedEvent(t.orderId(), type, mq.topic("workorder-event"), mq.tag(tagKey), payload);
    }

    private static java.sql.Timestamp ts(Instant i) { return i == null ? null : DbTimes.ts(i); }
}
