package com.smartmanufacturing.quality;
import org.springframework.http.HttpStatus;
import java.util.Locale;

/**
 * 契约中固定的 8 种业务错误类型（OpenAPI components.schemas.ProblemType），每种绑定唯一 HTTP 状态。
 * 调用方按 type 分支，不解析 detail；新增类型必须先修改契约。401/403 不在此列，统一为 about:blank。
 */
public enum ProblemType {
    VALIDATION_ERROR("validation-error", HttpStatus.BAD_REQUEST, "请求参数校验失败"),
    IDEMPOTENCY_KEY_CONFLICT("idempotency-key-conflict", HttpStatus.UNPROCESSABLE_ENTITY, "幂等键已用于不同的请求"),
    /** 处置单位与 NCR 单位不一致：请求格式合法但业务上不可处理，故为 422。 */
    UOM_MISMATCH("uom-mismatch", HttpStatus.UNPROCESSABLE_ENTITY, "处置单位与 NCR 单位不一致"),
    OVER_DISPOSE("over-dispose", HttpStatus.UNPROCESSABLE_ENTITY, "处置数量超过剩余待处置量"),
    INVALID_STATUS_TRANSITION("invalid-status-transition", HttpStatus.CONFLICT, "当前状态不允许该操作"),
    NCR_VOIDED("ncr-voided", HttpStatus.CONFLICT, "NCR 已作废"),
    NCR_NOT_FOUND("ncr-not-found", HttpStatus.NOT_FOUND, "NCR 不存在"),
    /** 关闭 NCR 时仍有 PENDING 或 FAILED 的库存回写任务。 */
    WRITEBACK_PENDING("writeback-pending", HttpStatus.CONFLICT, "库存回写未完成");

    private final String value;
    private final HttpStatus status;
    private final String title;
    ProblemType(String value, HttpStatus status, String title) { this.value = value; this.status = status; this.title = title; }
    /** 对外输出的 type 字符串。 */
    public String value() { return value; }
    public HttpStatus status() { return status; }
    public String title() { return title; }
    /** 指标标签取值：type 的下划线写法（如 over_dispose），与 observability.md 第 5 节一致。 */
    public String metricValue() { return value.replace('-', '_').toLowerCase(Locale.ROOT); }
}
