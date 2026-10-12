package com.smartmanufacturing.quality;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 质检服务的业务异常，由 {@link QualityProblemHandler} 转为 RFC 7807 响应。
 * HTTP 状态由 {@link ProblemType} 决定，调用处只能选择类型，避免同一类型出现不同状态码。
 */
public class ProblemException extends RuntimeException {
    private final ProblemType type;
    /** 扩展字段（本服务暂无，保留与库存服务一致的结构），按插入顺序输出。 */
    private final Map<String, Object> extensions;

    public ProblemException(ProblemType type, String detail, Map<String, Object> extensions) {
        super(detail);
        this.type = type;
        this.extensions = extensions == null ? Map.of() : new LinkedHashMap<>(extensions);
    }
    public ProblemException(ProblemType type, String detail) { this(type, detail, null); }

    /** 便捷构造：参数校验失败（400 validation-error）。 */
    public static ProblemException validation(String detail) { return new ProblemException(ProblemType.VALIDATION_ERROR, detail); }

    /** 便捷构造：NCR 不存在（404 ncr-not-found）。 */
    public static ProblemException notFound(String ncrNo) { return new ProblemException(ProblemType.NCR_NOT_FOUND, "NCR " + ncrNo + " 不存在"); }

    public ProblemType type() { return type; }
    public Map<String, Object> extensions() { return extensions; }
}
