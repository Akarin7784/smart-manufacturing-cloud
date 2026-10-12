package com.smartmanufacturing.workorder;
import com.smartmanufacturing.api.workorder.WorkOrderCancellationView;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * 取消工单的事务内逻辑（由 {@link OperationReportingService#cancel} 在已锁定工单行、已检查幂等记录的事务中调用）：
 * <ol>
 *   <li>状态校验：CREATED / RELEASED / ON_HOLD 可取消；已 CANCELLED 返回 409 workorder-cancelled（不再释放）；
 *       CLOSED 返回 409 workorder-closed；IN_PROGRESS / COMPLETED，或有过任何报工（含已冲销），返回 409 workorder-state-conflict（已有报工，请走完工或关闭）。</li>
 *   <li>同一事务内：工单置 CANCELLED、写状态审计与 WorkOrderCancelled 事件；如有 ACTIVE 预留，
 *       镜像置 RELEASING 并写入释放任务（reason=WORK_ORDER_CANCELLED，Idempotency-Key = cancel-{工单号}-{预留单号}）。</li>
 * </ol>
 * 释放由 {@link ReservationReleaseWorker} 异步执行：取消不依赖库存在线，库存恢复后自动完成。
 */
@Component
class WorkOrderCancellation {
    /** 允许取消的状态。 */
    static final Set<String> CANCELLABLE = Set.of("CREATED", "RELEASED", "ON_HOLD");
    /** 已有报工时的拒绝说明。 */
    static final String HAS_REPORTS = "已有报工，请走完工或关闭";
    private final JdbcTemplate jdbc;
    private final WorkOrderJournal journal;
    private final Clock clock;
    WorkOrderCancellation(JdbcTemplate jdbc, WorkOrderJournal journal, Clock clock) { this.jdbc = jdbc; this.journal = journal; this.clock = clock; }

    /** 取消释放的库存幂等键。 */
    static String releaseKey(String orderId, String reservationId) { return "cancel-" + orderId + "-" + reservationId; }

    WorkOrderCancellationView cancelInTx(String orderId, String status, String note, String operator) {
        switch (status) {
            case "CANCELLED" -> throw WorkOrderProblemType.WORKORDER_CANCELLED.exception("工单已取消：" + orderId);
            case "CLOSED" -> throw WorkOrderProblemType.WORKORDER_CLOSED.exception("工单已关闭，不能取消：" + orderId);
            default -> {
                if (!CANCELLABLE.contains(status))
                    throw WorkOrderProblemType.WORK_ORDER_STATE_CONFLICT.exception(HAS_REPORTS);
            }
        }
        // 只要有过任何报工（含已冲销的）就不能取消：冲销不抹掉实物流转，应走完工或关闭（如由 IN_PROGRESS 冻结后的 ON_HOLD）
        if (jdbc.queryForObject("select count(*) from wo_operation_report where order_id = ?", Integer.class, orderId) > 0)
            throw WorkOrderProblemType.WORK_ORDER_STATE_CONFLICT.exception(HAS_REPORTS);
        Instant now = clock.instant();
        // 工单行已在调用方事务内 FOR UPDATE 锁定，这里的条件更新只是兜底
        if (jdbc.update("update work_order set status = 'CANCELLED', version = version + 1 where id = ? and status = ?", orderId, status) != 1)
            throw WorkOrderProblemType.CONCURRENT_MODIFICATION.exception("工单状态已变化，请重试");
        // 不改 work_order_lifecycle / work_order_hold_reason：冻结与恢复先写这两张表再条件更新 work_order，
        // 本事务已先锁 work_order，若再写它们会形成相反的锁序而死锁。并发的冻结 / 恢复在条件更新 work_order 时失败并重读，
        // 随后看到 CANCELLED 返回 409 workorder-cancelled；冻结原因保留为历史记录。

        List<String> active = jdbc.queryForList("select reservation_id from wo_material_reservation where order_id = ? and status = 'ACTIVE'", String.class, orderId);
        String reservationId = active.isEmpty() ? null : active.get(0);
        if (reservationId != null) {
            jdbc.update("update wo_material_reservation set status = 'RELEASING' where reservation_id = ?", reservationId);
            jdbc.update("insert into wo_reservation_release_task (task_id, order_id, reservation_id, reason, idempotency_key, status, attempts, created_at) values (?, ?, ?, ?, ?, 'PENDING', 0, ?)",
                UUID.randomUUID().toString(), orderId, reservationId, MaterialReservationPort.ReleaseReason.WORK_ORDER_CANCELLED.name(),
                releaseKey(orderId, reservationId), DbTime.ts(now));
        }
        journal.statusChanged(orderId, status, "CANCELLED", "CANCEL", note, operator);
        var payload = new LinkedHashMap<String, Object>();
        payload.put("previousStatus", status);
        payload.put("reservationId", reservationId);
        journal.event(orderId, "WorkOrderCancelled", payload);
        return new WorkOrderCancellationView(orderId, "CANCELLED", status, reservationId, reservationId == null ? null : "PENDING", now);
    }
}
