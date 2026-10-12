package com.smartmanufacturing.workorder;
import org.springframework.http.HttpStatus;
import java.util.Arrays;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 工单服务固定的 RFC 7807 Problem type 枚举：每个值对应唯一的 type 标识与 HTTP 状态，调用方按 type 分支，不解析 detail。
 * 业务异常的业务码（BusinessException#code，历史响应中的 code 字段）一一映射到本枚举；新增业务码必须先在此登记，
 * WorkOrderProblemTypeTest 会扫描源码，发现未登记的业务码即失败。
 */
public enum WorkOrderProblemType {
    // ---- 工序报工（本次新增）
    /** 累计报工（合格 + 不良 + 报废）超过本工序上限：首道为计划数，后续为上道累计合格数；无容差。 */
    OVER_REPORT("over-report", "OVER_REPORT", HttpStatus.UNPROCESSABLE_ENTITY),
    /** 冲销上游报工后其累计合格数将小于下游已报总数。 */
    DOWNSTREAM_REPORTED("downstream-reported", "DOWNSTREAM_REPORTED", HttpStatus.CONFLICT),
    /** 工单已关闭（CLOSED），不再接受报工 / 冲销。 */
    WORKORDER_CLOSED("workorder-closed", "WORKORDER_CLOSED", HttpStatus.CONFLICT),
    /** 手工完工时存在没有任何有效报工的工序。 */
    OPERATIONS_UNREPORTED("operations-unreported", "OPERATIONS_UNREPORTED", HttpStatus.CONFLICT),
    /** 该报工已被冲销过（只允许冲销一次），或冲销的对象本身是冲销记录。 */
    REPORT_ALREADY_REVERSED("report-already-reversed", "REPORT_ALREADY_REVERSED", HttpStatus.CONFLICT),
    /** 报工 / 冲销对象不存在（工序号不在下达时的工艺快照中，或报工记录不存在）。 */
    OPERATION_NOT_FOUND("operation-not-found", "OPERATION_NOT_FOUND", HttpStatus.NOT_FOUND),
    REPORT_NOT_FOUND("report-not-found", "REPORT_NOT_FOUND", HttpStatus.NOT_FOUND),
    /** 报工计量单位与本工单已有报工的单位不一致（同一工单所有工序必须使用同一单位）。 */
    UOM_MISMATCH("uom-mismatch", "UOM_MISMATCH", HttpStatus.UNPROCESSABLE_ENTITY),

    // ---- 库存客户端与工单取消（本次新增）
    /** 工单已取消：重复取消（不同幂等键）以及取消后的其他写操作。 */
    WORKORDER_CANCELLED("workorder-cancelled", "WORKORDER_CANCELLED", HttpStatus.CONFLICT),
    /** 库存服务不可用（5xx、网络错误或服务令牌被拒），同步调用（下达、恢复时重新预留）失败。 */
    INVENTORY_SERVICE_UNAVAILABLE("inventory-service-unavailable", "INVENTORY_SERVICE_UNAVAILABLE", HttpStatus.SERVICE_UNAVAILABLE),

    // ---- 既有业务码
    VALIDATION_ERROR("validation-error", "INVALID_ARGUMENT", HttpStatus.BAD_REQUEST),
    IDEMPOTENCY_KEY_REQUIRED("idempotency-key-required", "IDEMPOTENCY_KEY_REQUIRED", HttpStatus.BAD_REQUEST),
    /** 同键不同请求体。 */
    IDEMPOTENCY_KEY_CONFLICT("idempotency-key-conflict", "IDEMPOTENCY_KEY_REUSED", HttpStatus.UNPROCESSABLE_ENTITY),
    /** 同键请求正在并发处理。 */
    IDEMPOTENCY_IN_PROGRESS("idempotency-in-progress", "IDEMPOTENCY_CONFLICT", HttpStatus.CONFLICT),
    AUTHENTICATION_REQUIRED("authentication-required", "AUTHENTICATION_REQUIRED", HttpStatus.UNAUTHORIZED),
    ACCESS_DENIED("access-denied", "ACCESS_DENIED", HttpStatus.FORBIDDEN),
    WORK_ORDER_NOT_FOUND("workorder-not-found", "WORK_ORDER_NOT_FOUND", HttpStatus.NOT_FOUND),
    WORK_ORDER_NOT_RELEASED("workorder-not-released", "WORK_ORDER_NOT_RELEASED", HttpStatus.NOT_FOUND),
    WORK_ORDER_STATE_CONFLICT("workorder-state-conflict", "WORK_ORDER_STATE_CONFLICT", HttpStatus.CONFLICT),
    CONCURRENT_MODIFICATION("concurrent-modification", "CONCURRENT_MODIFICATION", HttpStatus.CONFLICT),
    DEVICE_NOT_FOUND("device-not-found", "DEVICE_NOT_FOUND", HttpStatus.NOT_FOUND),
    DEVICE_OFFLINE("device-offline", "DEVICE_OFFLINE", HttpStatus.CONFLICT),
    DEVICE_SERVICE_UNAVAILABLE("device-service-unavailable", "DEVICE_SERVICE_UNAVAILABLE", HttpStatus.SERVICE_UNAVAILABLE),
    MASTERDATA_SERVICE_UNAVAILABLE("masterdata-service-unavailable", "MASTERDATA_SERVICE_UNAVAILABLE", HttpStatus.SERVICE_UNAVAILABLE),
    RELEASE_MASTERDATA_INVALID("release-masterdata-invalid", "RELEASE_MASTERDATA_INVALID", HttpStatus.UNPROCESSABLE_ENTITY),
    MATERIAL_SHORTAGE("material-shortage", "MATERIAL_SHORTAGE", HttpStatus.CONFLICT),
    INTERNAL_ERROR("internal-error", "INTERNAL_ERROR", HttpStatus.INTERNAL_SERVER_ERROR);

    private static final Map<String, WorkOrderProblemType> BY_CODE =
        Arrays.stream(values()).collect(Collectors.toMap(WorkOrderProblemType::code, Function.identity()));

    private final String type;
    private final String code;
    private final HttpStatus status;
    WorkOrderProblemType(String type, String code, HttpStatus status) { this.type = type; this.code = code; this.status = status; }

    /** RFC 7807 type 标识（相对 URI，与库存契约的写法一致）。 */
    public String type() { return type; }
    /** 历史业务码，作为 Problem 的扩展字段 code 保留，兼容既有调用方。 */
    public String code() { return code; }
    public HttpStatus status() { return status; }

    /** 按业务码查找；未登记的业务码返回 null（由处理器按内部错误处理并记录日志）。 */
    public static WorkOrderProblemType ofCode(String code) { return BY_CODE.get(code); }

    /** 以本类型构造业务异常，状态码与枚举保持一致。 */
    public com.smartmanufacturing.common.BusinessException exception(String detail) {
        return new com.smartmanufacturing.common.BusinessException(code, detail, status);
    }
    public com.smartmanufacturing.common.BusinessException exception(String detail, Object details) {
        return new com.smartmanufacturing.common.BusinessException(code, detail, status, details);
    }
}
