package com.smartmanufacturing.workorder;
import org.springframework.http.HttpStatus;
import java.util.Arrays;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 工单服务固定的 RFC 9457 Problem type 枚举：每个值对应唯一的 type 标识、固定中文 title 与 HTTP 状态，调用方按 type 分支，不解析 detail。
 * 业务异常的业务码（BusinessException#code，历史响应中的 code 字段）一一映射到本枚举；新增业务码必须先在此登记，
 * WorkOrderProblemTypeTest 会扫描源码，发现未登记的业务码即失败。
 */
public enum WorkOrderProblemType {
    // ---- 工序报工（本次新增）
    /** 累计报工（合格 + 不良 + 报废）超过本工序上限：首道为计划数，后续为上道累计合格数；无容差。 */
    OVER_REPORT("over-report", "报工数量超限", "OVER_REPORT", HttpStatus.UNPROCESSABLE_ENTITY),
    /** 冲销上游报工后其累计合格数将小于下游已报总数。 */
    DOWNSTREAM_REPORTED("downstream-reported", "下游工序已报工", "DOWNSTREAM_REPORTED", HttpStatus.CONFLICT),
    /** 工单已关闭（CLOSED），不再接受报工 / 冲销。 */
    WORKORDER_CLOSED("workorder-closed", "工单已关闭", "WORKORDER_CLOSED", HttpStatus.CONFLICT),
    /** 手工完工时存在没有任何有效报工的工序。 */
    OPERATIONS_UNREPORTED("operations-unreported", "存在未报工工序", "OPERATIONS_UNREPORTED", HttpStatus.CONFLICT),
    /** 该报工已被冲销过（只允许冲销一次），或冲销的对象本身是冲销记录。 */
    REPORT_ALREADY_REVERSED("report-already-reversed", "报工已冲销", "REPORT_ALREADY_REVERSED", HttpStatus.CONFLICT),
    /** 报工 / 冲销对象不存在（工序号不在下达时的工艺快照中，或报工记录不存在）。 */
    OPERATION_NOT_FOUND("operation-not-found", "工序不存在", "OPERATION_NOT_FOUND", HttpStatus.NOT_FOUND),
    REPORT_NOT_FOUND("report-not-found", "报工记录不存在", "REPORT_NOT_FOUND", HttpStatus.NOT_FOUND),
    /** 报工计量单位与本工单已有报工的单位不一致（同一工单所有工序必须使用同一单位）。 */
    UOM_MISMATCH("uom-mismatch", "计量单位不一致", "UOM_MISMATCH", HttpStatus.UNPROCESSABLE_ENTITY),

    // ---- 库存客户端与工单取消（本次新增）
    /** 工单已取消：重复取消（不同幂等键）以及取消后的其他写操作。 */
    WORKORDER_CANCELLED("workorder-cancelled", "工单已取消", "WORKORDER_CANCELLED", HttpStatus.CONFLICT),
    /** 库存服务不可用（5xx、网络错误或服务令牌被拒），同步调用（下达、恢复时重新预留）失败。 */
    INVENTORY_SERVICE_UNAVAILABLE("inventory-service-unavailable", "库存服务不可用", "INVENTORY_SERVICE_UNAVAILABLE", HttpStatus.SERVICE_UNAVAILABLE),

    // ---- M1 工序派工与任务状态（本次新增）
    /** 动作不允许在当前任务状态下执行：RUNNING 时改派、PAUSED 时报工、重复派工等。 */
    TASK_STATE_CONFLICT("task-state-conflict", "工序任务状态冲突", "TASK_STATE_CONFLICT", HttpStatus.CONFLICT),
    /** 任务未派工就开工 / 暂停 / 恢复 / 改派，或取消一个没有派过工的任务；报工不使用本类型（兼容未派工老数据）。 */
    TASK_NOT_DISPATCHED("task-not-dispatched", "工序未派工", "TASK_NOT_DISPATCHED", HttpStatus.CONFLICT),
    /** 工位不存在、已删除，或不属于该工序的工作中心。 */
    STATION_NOT_FOUND("station-not-found", "工位不存在", "STATION_NOT_FOUND", HttpStatus.UNPROCESSABLE_ENTITY),

    // ---- 既有业务码
    VALIDATION_ERROR("validation-error", "请求参数无效", "INVALID_ARGUMENT", HttpStatus.BAD_REQUEST),
    IDEMPOTENCY_KEY_REQUIRED("idempotency-key-required", "缺少幂等键", "IDEMPOTENCY_KEY_REQUIRED", HttpStatus.BAD_REQUEST),
    /** 同键不同请求体。 */
    IDEMPOTENCY_KEY_CONFLICT("idempotency-key-conflict", "幂等键冲突", "IDEMPOTENCY_KEY_REUSED", HttpStatus.UNPROCESSABLE_ENTITY),
    /** 同键请求正在并发处理。 */
    IDEMPOTENCY_IN_PROGRESS("idempotency-in-progress", "请求正在处理", "IDEMPOTENCY_CONFLICT", HttpStatus.CONFLICT),
    AUTHENTICATION_REQUIRED("authentication-required", "需要认证", "AUTHENTICATION_REQUIRED", HttpStatus.UNAUTHORIZED),
    ACCESS_DENIED("access-denied", "没有访问权限", "ACCESS_DENIED", HttpStatus.FORBIDDEN),
    WORK_ORDER_NOT_FOUND("workorder-not-found", "工单不存在", "WORK_ORDER_NOT_FOUND", HttpStatus.NOT_FOUND),
    WORK_ORDER_NOT_RELEASED("workorder-not-released", "工单未下达", "WORK_ORDER_NOT_RELEASED", HttpStatus.NOT_FOUND),
    WORK_ORDER_STATE_CONFLICT("workorder-state-conflict", "工单状态冲突", "WORK_ORDER_STATE_CONFLICT", HttpStatus.CONFLICT),
    CONCURRENT_MODIFICATION("concurrent-modification", "并发修改冲突", "CONCURRENT_MODIFICATION", HttpStatus.CONFLICT),
    DEVICE_NOT_FOUND("device-not-found", "设备不存在", "DEVICE_NOT_FOUND", HttpStatus.NOT_FOUND),
    DEVICE_OFFLINE("device-offline", "设备离线", "DEVICE_OFFLINE", HttpStatus.CONFLICT),
    DEVICE_SERVICE_UNAVAILABLE("device-service-unavailable", "设备服务不可用", "DEVICE_SERVICE_UNAVAILABLE", HttpStatus.SERVICE_UNAVAILABLE),
    MASTERDATA_SERVICE_UNAVAILABLE("masterdata-service-unavailable", "主数据服务不可用", "MASTERDATA_SERVICE_UNAVAILABLE", HttpStatus.SERVICE_UNAVAILABLE),
    RELEASE_MASTERDATA_INVALID("release-masterdata-invalid", "下达主数据无效", "RELEASE_MASTERDATA_INVALID", HttpStatus.UNPROCESSABLE_ENTITY),
    MATERIAL_SHORTAGE("material-shortage", "物料短缺", "MATERIAL_SHORTAGE", HttpStatus.CONFLICT),
    INTERNAL_ERROR("internal-error", "内部错误", "INTERNAL_ERROR", HttpStatus.INTERNAL_SERVER_ERROR);

    private static final Map<String, WorkOrderProblemType> BY_CODE =
        Arrays.stream(values()).collect(Collectors.toMap(WorkOrderProblemType::code, Function.identity()));

    private final String type;
    private final String title;
    private final String code;
    private final HttpStatus status;
    WorkOrderProblemType(String type, String title, String code, HttpStatus status) {
        this.type = type; this.title = title; this.code = code; this.status = status;
    }

    /** RFC 7807 type 标识（相对 URI，与库存契约的写法一致）。 */
    public String type() { return type; }
    /**
     * RFC 9457 title：每个 type 对应唯一固定的中文简述，与 docs/authentication.md 的工单服务表一致；
     * 具体原因放在 detail，title 不随请求变化。401/403 与 cloud-common SecurityProblem 保持同一文案。
     */
    public String title() { return title; }
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
