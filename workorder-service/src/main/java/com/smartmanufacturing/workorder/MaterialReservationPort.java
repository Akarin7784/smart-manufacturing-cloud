package com.smartmanufacturing.workorder;
import com.smartmanufacturing.api.workorder.ReservationLineView;
import java.time.Instant;
import java.util.List;

/**
 * 库存物料预留端口（契约由库存服务所有，P3 以 Feign 适配器实现 POST /api/inventory/reservations 等接口）：
 * <ul>
 *   <li>按工单整单预留，所有行原子成功，响应的每行带 lineId（消耗按行、按批次进行）；任一行短缺返回 409 并列出每行短缺量，
 *       适配器抛 {@link MaterialShortageException}，明细原样返回调用方。</li>
 *   <li>续约、释放均针对整张预留单；所有变更携带 Idempotency-Key，重复调用返回同一结果。</li>
 *   <li>到期由库存服务判定（TTL 15 分钟），经发件箱发往 RocketMQ topic {@code mes-inventory-reservation}（tag 为 Created/Released/Consumed/Expired，
 *       topic 与 tag 从 Nacos common.yml 的 manufacturing.mq.* 读取），本服务只订阅、按预留单号幂等消费，不自行计算过期。订阅（rocketmq-spring 5.x，消费组 CG_WORKORDER_RESERVATION）在 PR #13 合并后的后续 PR 接入。</li>
 * </ul>
 */
public interface MaterialReservationPort {
    /** 预留结果：库存的预留单号、到期时间与实际预留行（每行带 lineId）。 */
    record Reservation(String reservationId, Instant expiresAt, List<ReservationLineView> lines) {}

    /** 整单原子预留；同一幂等键重复调用必须返回同一预留单。 */
    Reservation reserve(String idempotencyKey, String orderId, List<ReservationLineView> lines);

    /**
     * 续约整张预留单，返回新的到期时间。
     * @throws ReservationGoneException 库存侧预留已过期或不存在（续约无法挽回）
     */
    Instant renew(String idempotencyKey, String reservationId);

    /** 释放整张预留单；必须幂等，对已释放或已过期的预留静默成功。 */
    void release(String idempotencyKey, String reservationId);

    /** 库存侧预留已失效：调用方应按“预留过期”流程处理，而不是重试。 */
    class ReservationGoneException extends RuntimeException {
        public ReservationGoneException(String reservationId) { super("预留已失效: " + reservationId); }
    }
}
