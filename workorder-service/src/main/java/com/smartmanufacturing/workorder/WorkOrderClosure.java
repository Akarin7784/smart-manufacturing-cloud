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
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 工单关闭与撤回下达（M1 PR2）。事务内逻辑由 {@link OperationReportingService} 在已锁定工单行、已检查幂等记录的事务中调用。
 * <ul>
 *   <li>关闭：COMPLETED → CLOSED，同一事务内为剩余 ACTIVE 预留登记释放任务（reason=WORK_ORDER_CLOSED，#72）。事务前先向质量服务确认该工单没有未结案 NCR（OPEN / UNDER_REVIEW / DISPOSED），
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

    private final QualityServiceToken serviceToken;

    WorkOrderClosure(JdbcTemplate jdbc, WorkOrderJournal journal, Clock clock, QualityClient quality,
                     org.springframework.beans.factory.ObjectProvider<QualityServiceToken> serviceToken) {
        this.jdbc = jdbc; this.journal = journal; this.clock = clock; this.quality = quality; this.serviceToken = serviceToken.getIfAvailable();
    }

    /** 关闭释放的库存幂等键（#72）。 */
    static String closeKey(String orderId, String reservationId) { return "close-" + orderId + "-" + reservationId; }

    /** 撤回释放的库存幂等键。 */
    static String withdrawKey(String orderId, String reservationId) { return "withdraw-" + orderId + "-" + reservationId; }

    /**
     * 查询质量服务：存在未结案 NCR 时 409 open-ncr-exists（data.openNcrCount）；调用失败时 503。
     * 以工单服务的服务身份调用（#74，调用者无需 quality:read）；质量服务 401 / 403 表示服务身份配置问题，同样 503 不放行。
     */
    void requireNoOpenNcr(String orderId) {
        long open = 0;
        for (String status : OPEN_NCR_STATUSES) {
            QualityClient.NcrPage page;
            try {
                page = quality.ncrs(orderId, status, 0, 1);
            } catch (FeignException.Unauthorized | FeignException.Forbidden e) {
                // 服务身份被拒（密钥 / scope 配置问题，不是调用者的权限问题）：401 丢弃缓存令牌；一律 503 不放行
                if (e instanceof FeignException.Unauthorized && serviceToken != null) serviceToken.invalidate();
                LOG.error("质量服务拒绝工单服务的服务身份（HTTP {}），拒绝关闭工单 {}", e.status(), orderId);
                throw WorkOrderProblemType.QUALITY_SERVICE_UNAVAILABLE.exception("质量服务拒绝工单服务的服务身份，无法确认 NCR 状态，工单未关闭");
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

    /** 只有 COMPLETED 可以关闭：已关闭 409 workorder-closed，已取消 409 workorder-cancelled，其他 409 workorder-state-conflict。 */
    static void requireClosable(String orderId, String status) {
        switch (status) {
            case "COMPLETED" -> { }
            case "CLOSED" -> throw WorkOrderProblemType.WORKORDER_CLOSED.exception("工单已关闭：" + orderId);
            case "CANCELLED" -> throw WorkOrderProblemType.WORKORDER_CANCELLED.exception("工单已取消，不能关闭：" + orderId);
            default -> throw WorkOrderProblemType.WORK_ORDER_STATE_CONFLICT.exception("只有 COMPLETED 状态的工单可以关闭，当前为 " + status);
        }
    }

    WorkOrderCloseView closeInTx(String orderId, String status, String note, String operator) {
        requireClosable(orderId, status);
        Instant now = clock.instant();
        if (jdbc.update("update work_order set status = 'CLOSED', version = version + 1 where id = ? and status = ?", orderId, status) != 1)
            throw WorkOrderProblemType.CONCURRENT_MODIFICATION.exception("工单状态已变化，请重试");
        // #72：同一事务内把剩余 ACTIVE 预留置 RELEASING 并登记释放任务，由 ReservationReleaseWorker 异步调用库存释放（Q1 不等结果）。
        // 只挑 ACTIVE：CONSUMED（完工已吃光）/ RELEASING / RELEASED / EXPIRED 不登记（Q4）；多张逐一登记（Q3）。
        // 工单行已在外层事务加锁，同一工单的关闭 / 取消串行，无需再对预留行 FOR UPDATE。
        List<String> active = jdbc.queryForList(
            "select reservation_id from wo_material_reservation where order_id = ? and status = 'ACTIVE' order by reservation_id", String.class, orderId);
        List<String> releasing = new ArrayList<>();
        for (String reservationId : active) {
            if (jdbc.update("update wo_material_reservation set status = 'RELEASING' where reservation_id = ? and status = 'ACTIVE'", reservationId) != 1) continue;
            CompensationReleaseTasks.insertInCurrentTx(jdbc, orderId, reservationId,
                MaterialReservationPort.ReleaseReason.WORK_ORDER_CLOSED, closeKey(orderId, reservationId), now);
            releasing.add(reservationId);
        }
        journal.statusChanged(orderId, status, "CLOSED", "CLOSE", note, operator);
        var payload = new LinkedHashMap<String, Object>();
        payload.put("previousStatus", status);
        payload.put("note", note);
        // Q6：转入 RELEASING 的预留单号（可为空数组）与是否有释放在途；释放结果仍以库存 ReservationReleased 为准
        payload.put("reservationIds", releasing);
        payload.put("releaseEnqueued", !releasing.isEmpty());
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
