package com.smartmanufacturing.inventory;
import org.springframework.http.HttpStatus;

/**
 * 契约中固定的 12 种业务错误类型（OpenAPI components.schemas.ProblemType），每种绑定唯一 HTTP 状态。
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
    VALIDATION_ERROR("validation-error", HttpStatus.BAD_REQUEST, "请求参数校验失败"),
    /** 请求计量单位与该物料已登记的现存量单位不一致：请求格式合法但业务上不可处理，故为 422 而非 400。 */
    UOM_MISMATCH("uom-mismatch", HttpStatus.UNPROCESSABLE_ENTITY, "计量单位与现存量单位不一致"),
    /** 以下三种 404 只用于质检隔离接口 /inventory/qc-hold/**（#55 拍板）；预留、发料、通用出入库行为不变（物料不存在仍为 409 insufficient-stock）。 */
    /** QC-HOLD 系统库位不存在或已停用（迁移种子缺失），质检回写不可重试。 */
    LOCATION_NOT_FOUND("location-not-found", HttpStatus.NOT_FOUND, "库位不存在"),
    /** 出隔离时物料从未登记（从未入过库）。 */
    MATERIAL_NOT_FOUND("material-not-found", HttpStatus.NOT_FOUND, "物料不存在"),
    /** 出隔离时该物料的批次从未登记。 */
    LOT_NOT_FOUND("lot-not-found", HttpStatus.NOT_FOUND, "批次不存在");

    private final String value;
    private final HttpStatus status;
    private final String title;
    ProblemType(String value, HttpStatus status, String title) { this.value = value; this.status = status; this.title = title; }
    /** 对外输出的 type 字符串。 */
    public String value() { return value; }
    public HttpStatus status() { return status; }
    public String title() { return title; }
}
