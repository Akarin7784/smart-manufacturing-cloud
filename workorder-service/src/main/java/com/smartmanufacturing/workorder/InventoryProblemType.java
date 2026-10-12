package com.smartmanufacturing.workorder;
import java.util.Arrays;

/**
 * 库存契约固定的 9 种 Problem type（docs/api/inventory-openapi.yaml 的 components.schemas.ProblemType）。
 * 客户端按 type 分支，不解析 detail；{@link InventoryReservationClient#toException} 对本枚举做穷举 switch，
 * 契约新增类型时编译即报错，必须显式决定处理方式。401 / 403 / 404 由框架返回 about:blank，按 HTTP 状态处理，不在此枚举中。
 */
enum InventoryProblemType {
    INSUFFICIENT_STOCK("insufficient-stock", 409),
    OVER_CONSUME("over-consume", 422),
    IDEMPOTENCY_KEY_CONFLICT("idempotency-key-conflict", 422),
    RESERVATION_EXPIRED("reservation-expired", 410),
    RESERVATION_RELEASED("reservation-released", 410),
    RESERVATION_CONSUMED("reservation-consumed", 409),
    LINE_CLOSED("line-closed", 409),
    VALIDATION_ERROR("validation-error", 400),
    UOM_MISMATCH("uom-mismatch", 422);

    private final String type;
    private final int status;
    InventoryProblemType(String type, int status) { this.type = type; this.status = status; }
    String type() { return type; }
    int status() { return status; }

    /** 按 type 查找；type 可能带前缀路径（如 /problems/insufficient-stock），取最后一段比较。未知返回 null。 */
    static InventoryProblemType of(String type) {
        if (type == null) return null;
        String t = type.substring(type.lastIndexOf('/') + 1);
        return Arrays.stream(values()).filter(v -> v.type.equals(t)).findFirst().orElse(null);
    }
}
