package com.smartmanufacturing.workorder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.UUID;

/**
 * 取消工单后的预留释放作业：执行 wo_reservation_release_task 中到期的 PENDING 任务（取消事务内写入，见 {@link WorkOrderCancellation}）。
 * <ul>
 *   <li>领取：短事务内 {@code SELECT … FOR UPDATE SKIP LOCKED} 并把 next_attempt_at 推后一个租期（默认 60 秒），提交后再在事务外调用库存（单次超时 3 秒），
 *       结果用短事务回写；不在持有行锁 / 连接时做 HTTP。</li>
 *   <li>410（契约外）：占用已归还，任务 DONE（result_status=GONE），计为 reservation_gone。</li>
 *   <li>幂等：库存 Idempotency-Key 固定为 {@code cancel-{工单号}-{预留单号}}，重试与重复执行只生效一次，重放返回首次结果。</li>
 *   <li>成功：2xx 且预留为 RELEASED / EXPIRED → DONE，本地镜像同步为该状态。</li>
 *   <li>409 reservation-consumed 或 404：不可释放，任务 STOPPED，写 wo_inventory_audit，并告警（日志 ERROR + 指标）。</li>
 *   <li>5xx / 网络错误：退避重试（1s、2s、4s…上限 5 分钟），并结束本批（库存不可用时继续只会让每条都等满超时）。</li>
 *   <li>401 / 403：同样退避重试，但同时告警（服务身份或权限配置问题）。</li>
 * </ul>
 */
@Component
public class ReservationReleaseWorker {
    private static final Logger LOG = LoggerFactory.getLogger(ReservationReleaseWorker.class);
    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;
    private final MaterialReservationPort materials;
    private final InventoryClientMetrics metrics;
    private final Clock clock;
    private final int batchSize;
    private final Duration lease;

    ReservationReleaseWorker(JdbcTemplate jdbc, TransactionTemplate tx, MaterialReservationPort materials, InventoryClientMetrics metrics,
                             Clock clock, @Value("${manufacturing.workorder.reservation-release.batch-size:20}") int batchSize,
                             @Value("${manufacturing.workorder.reservation-release.lease:PT60S}") Duration lease) {
        this.lease = lease;
        this.jdbc = jdbc; this.tx = tx; this.materials = materials; this.metrics = metrics; this.clock = clock; this.batchSize = batchSize;
    }

    private record Task(String taskId, String orderId, String reservationId, String reason, String key, int attempts) {}

    /**
     * 执行一批到期任务，返回本批进入终态（DONE / STOPPED）的任务数。
     * 领取与回写各自是短事务，HTTP 调用在事务之外：领取事务用 SKIP LOCKED 选出到期任务并把 next_attempt_at 推后一个租期（lease），
     * 提交后其他实例在租期内不会再领取；调用结束后按结果回写（条件 status = 'PENDING'）。实例在租期内崩溃时任务到期后被重新领取，
     * 库存侧按同一 Idempotency-Key 幂等，重复调用安全。
     */
    public int runBatch() {
        List<Task> batch = tx.execute(s -> {
            List<Task> tasks = jdbc.query("select task_id, order_id, reservation_id, reason, idempotency_key, attempts from wo_reservation_release_task"
                    + " where status = 'PENDING' and (next_attempt_at is null or next_attempt_at <= ?) order by created_at, task_id limit " + batchSize
                    + " for update skip locked",
                (rs, i) -> new Task(rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4), rs.getString(5), rs.getInt(6)),
                DbTimes.ts(clock.instant()));
            Timestamp leaseUntil = DbTimes.ts(clock.instant().plus(lease));
            for (Task t : tasks) jdbc.update("update wo_reservation_release_task set next_attempt_at = ? where task_id = ?", leaseUntil, t.taskId());
            return tasks;
        });
        int finished = 0;
        for (Task t : batch == null ? List.<Task>of() : batch) {
            Boolean ok = execute(t);
            if (ok == null) {
                // 可重试失败：结束本批，未尝试的任务释放租约，下一轮立即可领取
                List<Task> rest = batch.subList(batch.indexOf(t) + 1, batch.size());
                for (Task r : rest) jdbc.update("update wo_reservation_release_task set next_attempt_at = null where task_id = ? and status = 'PENDING'", r.taskId());
                break;
            }
            finished++;
        }
        return finished;
    }

    /** 执行单个任务（HTTP 在事务外），结果在短事务中回写：true = 进入终态，null = 可重试失败。 */
    private Boolean execute(Task t) {
        try {
            String status = materials.release(t.key(), t.reservationId(), MaterialReservationPort.ReleaseReason.valueOf(t.reason()));
            tx.executeWithoutResult(s -> {
                finish(t, "DONE", status, null);
                jdbc.update("update wo_material_reservation set status = ? where reservation_id = ? and status in ('RELEASING', 'ACTIVE')", status, t.reservationId());
                audit(t, "RELEASE_" + status, null);
            });
            metrics.releaseResult(status.toLowerCase());
            LOG.info("工单 {} 取消后释放预留 {} 完成：{}", t.orderId(), t.reservationId(), status);
            return true;
        } catch (MaterialReservationPort.ReservationGoneException e) {
            // 410：契约外（正常应为 200），但占用已归还，任务进入终态 DONE，单独计为 reservation_gone，便于发现库存实现偏差
            String mirror = e.status() == null ? "RELEASED" : e.status();
            tx.executeWithoutResult(s -> {
                finish(t, "DONE", "GONE", e.getMessage());
                jdbc.update("update wo_material_reservation set status = ? where reservation_id = ? and status in ('RELEASING', 'ACTIVE')", mirror, t.reservationId());
                audit(t, "RELEASE_GONE", "库存返回 410，终态 " + mirror);
            });
            metrics.releaseResult("reservation_gone");
            LOG.warn("释放预留 {} 时库存返回 410（{}），按终态处理", t.reservationId(), mirror);
            return true;
        } catch (MaterialReservationPort.ReservationConsumedException e) {
            stop(t, "RESERVATION_CONSUMED", "CONSUMED", "reservation_consumed", e);
            return true;
        } catch (MaterialReservationPort.ReservationNotFoundException e) {
            stop(t, "RESERVATION_NOT_FOUND", null, "reservation_not_found", e);
            return true;
        } catch (InventoryAuthException e) {
            // 身份 / 权限问题：重试（令牌会重新申请）并告警
            LOG.error("【告警】释放预留 {} 被库存拒绝（HTTP {}），将退避重试；请检查 workorder-service 服务身份配置", t.reservationId(), e.httpStatus(), e);
            return retry(t, "auth_error", e);
        } catch (InventoryUnavailableException e) {
            LOG.warn("库存不可用，释放预留 {} 退避重试：{}", t.reservationId(), e.getMessage());
            return retry(t, "unavailable", e);
        } catch (RuntimeException e) {
            LOG.error("释放预留 {} 失败，退避重试", t.reservationId(), e);
            return retry(t, "error", e);
        }
    }

    /** 不可释放：停止任务、写审计并告警，需要人工处理（例如核对已扣减物料的去向）。 */
    private void stop(Task t, String event, String mirrorStatus, String result, RuntimeException e) {
        tx.executeWithoutResult(s -> {
            finish(t, "STOPPED", null, e.getMessage());
            if (mirrorStatus != null)
                jdbc.update("update wo_material_reservation set status = ? where reservation_id = ? and status in ('RELEASING', 'ACTIVE')", mirrorStatus, t.reservationId());
            audit(t, event, e.getMessage());
        });
        metrics.releaseResult(result);
        LOG.error("【告警】工单 {} 已取消，但预留 {} 不能释放（{}），任务已停止，请人工处理", t.orderId(), t.reservationId(), event);
    }

    private Boolean retry(Task t, String result, RuntimeException e) {
        Duration backoff = WorkOrderOutboxRelay.backoff(t.attempts() + 1);
        jdbc.update("update wo_reservation_release_task set attempts = attempts + 1, next_attempt_at = ?, last_error = ? where task_id = ? and status = 'PENDING'",
            DbTimes.ts(clock.instant().plus(backoff)), truncate(e.getMessage()), t.taskId());
        metrics.releaseResult(result);
        return null;
    }

    private void finish(Task t, String status, String resultStatus, String error) {
        jdbc.update("update wo_reservation_release_task set status = ?, result_status = ?, attempts = attempts + 1, last_error = ?, completed_at = ? where task_id = ? and status = 'PENDING'",
            status, resultStatus, truncate(error), DbTimes.ts(clock.instant()), t.taskId());
    }

    private void audit(Task t, String event, String detail) {
        jdbc.update("insert into wo_inventory_audit (id, order_id, reservation_id, event, detail, created_at) values (?, ?, ?, ?, ?, ?)",
            UUID.randomUUID().toString(), t.orderId(), t.reservationId(), event, truncate(detail), DbTimes.ts(clock.instant()));
    }

    private static String truncate(String s) { return s == null ? null : s.length() > 500 ? s.substring(0, 500) : s; }

    /** 定时调度；测试中以 manufacturing.workorder.reservation-release.enabled=false 关闭，由用例直接调用 runBatch。 */
    @Component
    @ConditionalOnProperty(name = "manufacturing.workorder.reservation-release.enabled", havingValue = "true", matchIfMissing = true)
    static class Schedule {
        private final ReservationReleaseWorker worker;
        Schedule(ReservationReleaseWorker worker) { this.worker = worker; }

        @Scheduled(fixedDelayString = "${manufacturing.workorder.reservation-release.interval:PT2S}",
                   initialDelayString = "${manufacturing.workorder.reservation-release.interval:PT2S}")
        void run() {
            try { worker.runBatch(); } catch (RuntimeException e) { LOG.error("预留释放作业异常，下一轮重试", e); }
        }
    }
}
