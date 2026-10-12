package com.smartmanufacturing.workorder;
import com.smartmanufacturing.api.workorder.ReservationLineView;
import java.time.Instant;
import java.util.List;

/**
 * 库存物料预留端口（契约由库存服务所有，见 docs/api/inventory-openapi.yaml；生产实现为 {@link InventoryReservationClient}，经 Nacos 服务发现直连库存服务）：
 * <ul>
 *   <li>按工单整单预留，所有行原子成功，响应的每行带 lineId（消耗按行、按批次进行）；任一行短缺返回 409 并列出每行短缺量，
 *       适配器抛 {@link MaterialShortageException}，明细原样返回调用方。</li>
 *   <li>续约、释放均针对整张预留单；所有变更携带 Idempotency-Key，重复调用返回同一结果。</li>
 *   <li>到期由库存服务判定（TTL 15 分钟），经发件箱发往 RocketMQ topic {@code mes-inventory-reservation}（tag 为 Created/Released/Consumed/Expired，
 *       topic 与 tag 从 Nacos common.yml 的 manufacturing.mq.* 读取），本服务只订阅（{@link ReservationEventConsumer}，消费组 CG_WORKORDER_RESERVATION），
 *       按 eventId 幂等、按 aggregateVersion 丢弃过时事件，不自行计算过期。</li>
 *   <li>续约只允许持有该预留的调用方（创建它的服务身份），否则库存返回 403；续约不发事件，以续约响应的 expiresAt 为准。</li>
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

    /** 释放原因，与库存契约 ReleaseReason 一致。 */
    enum ReleaseReason { CALLER_REQUEST, WORK_ORDER_CANCELLED, COMPENSATION }

    /**
     * 释放整张预留单，返回库存答复的预留单状态（RELEASED 或 EXPIRED，二者都视为成功）。必须幂等：同一幂等键重放返回首次结果。
     * @throws ReservationConsumedException 预留已整单扣减（409 reservation-consumed），不可释放，调用方不应重试
     * @throws ReservationNotFoundException 库存不存在该预留单（404），调用方不应重试
     * @throws InventoryAuthException 服务令牌被拒（401 / 403），可重试但需告警
     * @throws InventoryUnavailableException 库存不可用（5xx / 网络），退避重试
     */
    String release(String idempotencyKey, String reservationId, ReleaseReason reason);

    /** 下达失败等场景的补偿释放（reason=COMPENSATION）。 */
    default void release(String idempotencyKey, String reservationId) { release(idempotencyKey, reservationId, ReleaseReason.COMPENSATION); }

    /** 预留单已整单扣减（库存 409 reservation-consumed）：不能再释放。 */
    class ReservationConsumedException extends RuntimeException {
        public ReservationConsumedException(String reservationId) { super("预留已整单扣减，不能释放: " + reservationId); }
    }

    /** 库存不存在该预留单（404）。 */
    class ReservationNotFoundException extends RuntimeException {
        public ReservationNotFoundException(String reservationId) { super("库存不存在预留单: " + reservationId); }
    }

    /** 库存侧预留已失效：调用方应按“预留过期”流程处理，而不是重试。 */
    class ReservationGoneException extends RuntimeException {
        public ReservationGoneException(String reservationId) { super("预留已失效: " + reservationId); }
    }
}
