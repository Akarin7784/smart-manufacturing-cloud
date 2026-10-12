package com.smartmanufacturing.quality;

/**
 * 调用库存 QC-HOLD 内部接口的端口（直连 inventory-service 的 /inventory/qc-hold/**，不经网关）。
 * 生产实现为 {@link HttpInventoryQcHoldClient}；测试以替身取代（{@link WritebackClientConfiguration} 对本接口使用 ConditionalOnMissingBean）。
 * 实现不得抛出异常：所有结果（含网络错误）都折算为 {@link Outcome}，由 {@link WritebackWorker} 决定重试或失败。
 */
public interface InventoryQcHoldClient {
    /** 回写动作（契约 WritebackTask.action）。 */
    enum Action { HOLD, SCRAP, RELEASE, VOID }

    /**
     * 一次调用的请求内容。
     * @param reason        RELEASE 为 REWORK/CONCESSION，VOID 为 NCR_VOIDED，其余为 null
     * @param dispositionId RELEASE/SCRAP 必填，HOLD/VOID 为 null（库存按 reason 校验）
     * @param qty           已规范化的数量字符串（stripTrailingZeros().toPlainString()）
     */
    record Call(Action action, String idempotencyKey, String ncrNo, String materialCode, String uom, String lotNo,
                String qty, String reason, Long dispositionId) {}

    /** 调用结果。 */
    sealed interface Outcome {}
    /** 库存已记账（201）；replayed 表示库存按原幂等键重放了首次结果（响应头 Idempotent-Replayed: true）。 */
    record Booked(Long txnId, boolean replayed) implements Outcome {}
    /** 可重试：网络错误、超时、5xx、429、401（已刷新令牌重试一次仍失败）、403、令牌申请失败等，库存是否记账可能不确定。 */
    record Retryable(String error) implements Outcome {}
    /**
     * 不可重试：库存明确拒绝（400、409 insufficient-stock、422 等）。
     * certain=true 表示可确定库存未记账（作废时据此自动取消 VOID）；false 表示拒绝本身不能证明未记账（如 422 idempotency-key-conflict）。
     */
    record Rejected(String error, boolean certain) implements Outcome {}

    /** 是否具备调用条件（已配置服务密钥）。未配置时调度器跳过本轮，任务保持 PENDING，可从 pending 指标观察。 */
    boolean ready();

    /** 执行一次调用，不抛异常。 */
    Outcome call(Call call);
}
