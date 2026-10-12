package com.smartmanufacturing.inventory;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 库存服务的业务异常，由 {@link InventoryProblemHandler} 转为 RFC 7807 响应。
 * HTTP 状态由 {@link ProblemType} 决定，调用处只能选择类型，避免同一类型出现不同状态码。
 */
public class ProblemException extends RuntimeException {
    private final ProblemType type;
    /** 扩展字段（如 shortages），按插入顺序输出。 */
    private final Map<String, Object> extensions;

    public ProblemException(ProblemType type, String detail, Map<String, Object> extensions) {
        super(detail);
        this.type = type;
        this.extensions = extensions == null ? Map.of() : new LinkedHashMap<>(extensions);
    }
    public ProblemException(ProblemType type, String detail) { this(type, detail, null); }

    /** 便捷构造：参数校验失败（400 validation-error）。 */
    public static ProblemException validation(String detail) { return new ProblemException(ProblemType.VALIDATION_ERROR, detail); }

    /** 便捷构造：请求单位与该物料已登记的现存量单位不一致（422 uom-mismatch），入库、出库与预留共用。 */
    public static ProblemException uomMismatch(String material, String existing, String requested) {
        return new ProblemException(ProblemType.UOM_MISMATCH, "物料 " + material + " 的现存量单位为 " + existing + "，与请求的 " + requested + " 不一致");
    }

    public ProblemType type() { return type; }
    public Map<String, Object> extensions() { return extensions; }
}
