package com.smartmanufacturing.inventory;
import org.springframework.http.HttpStatus;

/**
 * 契约中固定的 8 种业务错误类型（OpenAPI components.schemas.ProblemType），每种绑定唯一 HTTP 状态。
 * 调用方按 type 分支，不解析 detail；新增类型必须先修改契约。
 */
public enum ProblemType {
    INSUFFICIENT_STOCK("insufficient-stock", HttpStatus.CONFLICT, "可用量不足"),
    OVER_CONSUME("over-consume", HttpStatus.UNPROCESSABLE_ENTITY, "扣减数量超过剩余可扣减量"),
    IDEMPOTENCY_KEY_CONFLICT("idempotency-key-conflict", HttpStatus.UNPROCESSABLE_ENTITY, "幂等键已用于不同的请求"),
    RESERVATION_EXPIRED("reservation-expired", HttpStatus.GONE, "预留单已过期"),
    RESERVATION_RELEASED("reservation-released", HttpStatus.GONE, "预留单已释放"),
    RESERVATION_CONSUMED("reservation-consumed", HttpStatus.CONFLICT, "预留单已整单扣减"),
    LINE_CLOSED("line-closed", HttpStatus.CONFLICT, "预留行已关闭"),
    VALIDATION_ERROR("validation-error", HttpStatus.BAD_REQUEST, "请求参数校验失败");

    private final String value;
    private final HttpStatus status;
    private final String title;
    ProblemType(String value, HttpStatus status, String title) { this.value = value; this.status = status; this.title = title; }
    /** 对外输出的 type 字符串。 */
    public String value() { return value; }
    public HttpStatus status() { return status; }
    public String title() { return title; }
}
