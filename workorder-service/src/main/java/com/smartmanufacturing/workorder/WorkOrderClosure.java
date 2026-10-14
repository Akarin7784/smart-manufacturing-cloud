package com.smartmanufacturing.workorder;

import com.smartmanufacturing.api.workorder.WorkOrderCloseView;
import com.smartmanufacturing.api.workorder.WorkOrderWithdrawView;
import com.smartmanufacturing.common.BusinessException;
import feign.FeignException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 工单关闭与撤回下达（M1 PR2）。事务内逻辑由 {@link OperationReportingService} 在已锁定工单行、已检查幂等记录的事务中调用。
 * <ul>
 *   <li>关闭：COMPLETED → CLOSED。事务前先向质量服务确认该工单没有未结案 NCR（OPEN / UNDER_REVIEW / DISPOSED），
 *       有则 409 open-ncr-exists；质量服务不可用一律 503 quality-service-unavailable，<b>不放行</b>。</li>
 *   <li>撤回下达：RELEASED → CREATED。要求没有任何报工、没有开工过的任务；删除下达快照（生命周期、冻结原因、BOM 快照、工序任务与进度），
 *       保留审计（状态日志、任务日志），有效预留置 RELEASING 并登记释放任务（reason=CALLER_REQUEST）。</li>
 * </ul>
 */
@Component
class WorkOrderClosure {
    private static final Logger LOG = LoggerFactory.getLogger(WorkOrderClosure.class);
    /** 未结案的 NCR 状态（CLOSED / VOIDED 视为已结案）。 */
    static final List<String> OPEN_NCR_STATUSES = List.of("OPEN", "UNDER_REVIEW", "DISPOSED");

    private final JdbcTemplate jdbc;
    private final WorkOrderJournal journal;
    private final Clock clock;
    private final QualityClient quality;

    WorkOrderClosure(JdbcTemplate jdbc, WorkOrderJournal journal, Clock clock, QualityClient quality) {
        this.jdbc = jdbc; this.journal = journal; this.clock = clock; this.quality = quality;
    }

    /** 撤回释放的库存幂等键。 */
    static String withdrawKey(String orderId, String reservationId) { return "withdraw-" + orderId + "-" + reservationId; }

    /**
     * 查询质量服务：存在未结案 NCR 时 409 open-ncr-exists（data.openNcrCount）；调用失败时 503。
     * 403 原样返回 access-denied（调用者缺少 quality:read，属于权限配置问题而非服务故障）。
     */
    void requireNoOpenNcr(String orderId) {
        long open = 0;
        for (String status : OPEN_NCR_STATUSES) {
            QualityClient.NcrPage page;
            try {
                page = quality.ncrs(orderId, status, 0, 1);
            } catch (FeignException.Forbidden e) {
                throw WorkOrderProblemType.ACCESS_DENIED.exception("没有查询 NCR 的权限（需要 quality:read），不能关闭工单");
            } catch (BusinessException e) {
                throw e;
            } catch (RuntimeException e) {
                LOG.warn("质量服务调用失败，拒绝关闭工单 {}", orderId, e);
                throw WorkOrderProblemType.QUALITY_SERVICE_UNAVAILABLE.exception("质量服务暂时不可用，无法确认 NCR 状态，工单未关闭");
            }
            if (page == null)
                throw WorkOrderProblemType.QUALITY_SERVICE_UNAVAILABLE.exception("质量服务返回空结果，无法确认 NCR 状态，工单未关闭");
            open += page.total();
        }
        if (open > 0)
            throw WorkOrderProblemType.OPEN_NCR_EXISTS.exception("工单 " + orderId + " 还有 " + open + " 张未结案的 NCR，不能关闭",
                Map.of("openNcrCount", open));
    }

    WorkOrderCloseView closeInTx(String orderId, String status, String note, String operator) {
        switch (status) {
            case "COMPLETED" -> { }
            case "CLOSED" -> throw WorkOrderProblemType.WORKORDER_CLOSED.exception("工单已关闭：" + orderId);
            case "CANCELLED" -> throw WorkOrderProblemType.WORKORDER_CANCELLED.exception("工单已取消，不能关闭：" + orderId);
            default -> throw WorkOrderProblemType.WORK_ORDER_STATE_CONFLICT.exception("只有 COMPLETED 状态的工单可以关闭，当前为 " + status);
        }
        Instant now = clock.instant();
        if (jdbc.update("update work_order set status = 'CLOSED', version = version + 1 where id = ? and status = ?", orderId, status) != 1)
            throw WorkOrderProblemType.CONCURRENT_MODIFICATION.exception("工单状态已变化，请重试");
        journal.statusChanged(orderId, status, "CLOSED", "CLOSE", note, operator);
        var payload = new LinkedHashMap<String, Object>();
        payload.put("previousStatus", status);
        payload.put("note", note);
        journal.event(orderId, "WorkOrderClosed", payload);
        return new WorkOrderCloseView(orderId, "CLOSED", status, now);
    }

    WorkOrderWithdrawView withdrawInTx(String orderId, String status, String note, String operator) {
        switch (status) {
            case "RELEASED" -> { }
            case "CLOSED" -> throw WorkOrderProblemType.WORKORDER_CLOSED.exception("工单已关闭，不能撤回下达：" + orderId);
            case "CANCELLED" -> throw WorkOrderProblemType.WORKORDER_CANCELLED.exception("工单已取消，不能撤回下达：" + orderId);
            default -> throw WorkOrderProblemType.WORK_ORDER_STATE_CONFLICT.exception("只有 RELEASED 状态的工单可以撤回下达，当前为 " + status);
        }
        if (jdbc.queryForObject("select count(*) from wo_operation_report where order_id = ?", Integer.class, orderId) > 0)
            throw WorkOrderProblemType.WORK_ORDER_STATE_CONFLICT.exception("工单已有报工，不能撤回下达");
        if (jdbc.queryForObject("select count(*) from operation_task where order_id = ? and (started_at is not null or status in ('RUNNING', 'PAUSED', 'DONE'))",
                Integer.class, orderId) > 0)
            throw WorkOrderProblemType.WORK_ORDER_STATE_CONFLICT.exception("工单已有工序开工，不能撤回下达");
        Map<String, Object> frozen = jdbc.queryForList("select release_key, product_code, bom_version, routing_version from work_order_lifecycle where order_id = ?", orderId)
            .stream().findFirst().orElse(null);
        String releaseKey = frozen == null ? null : (String) frozen.get("release_key");
        Instant now = clock.instant();
        if (jdbc.update("update work_order set status = 'CREATED', version = version + 1 where id = ? and status = ?", orderId, status) != 1)
            throw WorkOrderProblemType.CONCURRENT_MODIFICATION.exception("工单状态已变化，请重试");

        List<String> active = jdbc.queryForList("select reservation_id from wo_material_reservation where order_id = ? and status = 'ACTIVE'", String.class, orderId);
        String reservationId = active.isEmpty() ? null : active.get(0);
        if (reservationId != null) {
            jdbc.update("update wo_material_reservation set status = 'RELEASING' where reservation_id = ?", reservationId);
            jdbc.update("insert into wo_reservation_release_task (task_id, order_id, reservation_id, reason, idempotency_key, status, attempts, created_at) values (?, ?, ?, ?, ?, 'PENDING', 0, ?)",
                UUID.randomUUID().toString(), orderId, reservationId, MaterialReservationPort.ReleaseReason.CALLER_REQUEST.name(),
                withdrawKey(orderId, reservationId), DbTimes.ts(now));
        }
        // 删除下达快照，使工单可以按新的主数据重新下达；预留镜像、状态日志与任务日志保留为历史
        jdbc.update("delete from wo_operation_progress where order_id = ?", orderId);
        jdbc.update("delete from operation_task where order_id = ?", orderId);
        jdbc.update("delete from wo_bom_snapshot where order_id = ?", orderId);
        jdbc.update("delete from work_order_hold_reason where order_id = ?", orderId);
        jdbc.update("delete from work_order_lifecycle where order_id = ?", orderId);
        // 记录被撤回的下达键：同键重新下达返回 409（否则会重放到已释放的同一张预留）
        if (releaseKey != null)
            jdbc.update("insert into wo_release_withdrawal (order_id, release_key, reservation_id, withdrawn_by, withdrawn_at, product_code, bom_version, routing_version) values (?, ?, ?, ?, ?, ?, ?, ?)",
                orderId, releaseKey, reservationId, operator, DbTimes.ts(now), frozen.get("product_code"), frozen.get("bom_version"), frozen.get("routing_version"));
        journal.statusChanged(orderId, status, "CREATED", "WITHDRAW_RELEASE", note, operator);
        var payload = new LinkedHashMap<String, Object>();
        payload.put("previousStatus", status);
        payload.put("reservationId", reservationId);
        payload.put("note", note);
        journal.event(orderId, "WorkOrderReleaseWithdrawn", payload);
        return new WorkOrderWithdrawView(orderId, "CREATED", status, reservationId, reservationId == null ? null : "PENDING", now);
    }
}
