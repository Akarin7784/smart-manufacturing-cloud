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

    /**
     * 需要额外计数的信号（由执行器在结果回写提交后计入指标）：
     * AUTH → mes_quality_inventory_auth_failure_total{operation=hold|release|scrap}；TOKEN_AUTH → 同一指标 operation=token；
     * CONTRACT → mes_quality_inventory_contract_mismatch_total{operation}（库存返回了契约之外或说明请求构造有误的响应）。
     */
    enum Signal { NONE, AUTH, TOKEN_AUTH, CONTRACT }

    /** 调用结果。 */
    sealed interface Outcome {}
    /**
     * 库存已记账（2xx）；replayed 表示库存按原幂等键重放了首次结果（响应头 Idempotent-Replayed: true）。
     * contractMismatch：2xx 但响应体缺少 txnId（仍按已记账处理，同时计契约不一致）。
     */
    record Booked(Long txnId, boolean replayed, boolean contractMismatch) implements Outcome {
        public Booked(Long txnId, boolean replayed) { this(txnId, replayed, false); }
    }
    /**
     * 重试原因，即 mes_quality_writeback_retry_total 的 reason 标签：
     * NOT_FOUND_UNKNOWN 为三种约定 type 以外的 404（gateway-not-found、about:blank、未定义 type），次数受 max-unknown-404-retries 限制；
     * SERVER 为 5xx（含令牌端点 5xx）；TOO_MANY 为 429；NETWORK 为连接失败、超时、408、令牌端点不可达。
     */
    enum RetryReason {
        NOT_FOUND_UNKNOWN("not_found_unknown"), SERVER("5xx"), TOO_MANY("429"), NETWORK("network");
        private final String label;
        RetryReason(String label) { this.label = label; }
        /** 指标标签取值。 */
        public String label() { return label; }
    }

    /** 可重试：退避后重试，库存是否记账可能不确定（同一幂等键重放安全）。 */
    record Retryable(String error, RetryReason reason) implements Outcome {
        public Retryable(String error) { this(error, RetryReason.NETWORK); }
    }
    /**
     * 不可重试，任务直接 FAILED：库存拒绝（400、403、404、409、422，401 刷新令牌重试一次后仍 401，令牌端点 4xx 等）。
     * certain=true 表示可确定库存未记账（作废时据此自动取消 VOID）；false 表示拒绝本身不能证明未记账（如 422 idempotency-key-conflict），VOID 保持 PENDING。
     */
    record Rejected(String error, boolean certain, Signal signal) implements Outcome {
        public Rejected(String error, boolean certain) { this(error, certain, Signal.NONE); }
    }

    /** 指标 operation 标签：HOLD → hold；SCRAP → scrap；RELEASE、VOID 都调用 releases → release。 */
    static String operation(Action action) {
        return switch (action) { case HOLD -> "hold"; case SCRAP -> "scrap"; case RELEASE, VOID -> "release"; };
    }

    /** 是否具备调用条件（已配置服务密钥）。未配置时调度器跳过本轮，任务保持 PENDING，可从 pending 指标观察。 */
    boolean ready();

    /** 执行一次调用，不抛异常。 */
    Outcome call(Call call);
}
