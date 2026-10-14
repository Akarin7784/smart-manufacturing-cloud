package com.smartmanufacturing.masterdata;
import java.util.Arrays;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 主数据服务固定的 RFC 9457 Problem type 枚举（#71）：写法与工单服务 WorkOrderProblemType 一致——
 * type 为 kebab-case 相对 URI，title 为固定中文简述，code 为历史业务码（作为扩展字段保留）。
 * HTTP 状态沿用抛出处 BusinessException#status（同一业务码在不同场景可能是 404 或 422，如 WORK_CENTER_NOT_FOUND）。
 * 新增业务码必须先在此登记，MasterdataProblemTypeTest 会扫描源码，发现未登记的业务码即失败。
 */
enum MasterdataProblemType {
    // ---- 物料
    MATERIAL_NOT_FOUND("material-not-found", "物料不存在", "MATERIAL_NOT_FOUND"),
    MATERIAL_EXISTS("material-exists", "物料编码已存在", "MATERIAL_EXISTS"),
    MATERIAL_VERSION_CONFLICT("material-version-conflict", "物料已被他人修改", "MATERIAL_VERSION_CONFLICT"),
    // ---- BOM
    BOM_NOT_FOUND("bom-not-found", "BOM 不存在", "BOM_NOT_FOUND"),
    BOM_NOT_EFFECTIVE("bom-not-effective", "无生效 BOM", "BOM_NOT_EFFECTIVE"),
    BOM_PRODUCT_NOT_MAKE("bom-product-not-make", "采购件不能建立 BOM", "BOM_PRODUCT_NOT_MAKE"),
    BOM_VERSION_EXISTS("bom-version-exists", "BOM 版本已存在", "BOM_VERSION_EXISTS"),
    BOM_KEY_MISMATCH("bom-key-mismatch", "BOM 路径与请求体不一致", "BOM_KEY_MISMATCH"),
    BOM_NOT_DRAFT("bom-not-draft", "BOM 不是草稿", "BOM_NOT_DRAFT"),
    BOM_NOT_RELEASED("bom-not-released", "BOM 未发布", "BOM_NOT_RELEASED"),
    BOM_RELEASE_CONFLICT("bom-release-conflict", "BOM 发布冲突", "BOM_RELEASE_CONFLICT"),
    BOM_COMPONENT_NOT_FOUND("bom-component-not-found", "BOM 组件物料不存在", "BOM_COMPONENT_NOT_FOUND"),
    BOM_COMPONENT_INACTIVE("bom-component-inactive", "BOM 组件物料已停用", "BOM_COMPONENT_INACTIVE"),
    BOM_ROUTING_MISSING("bom-routing-missing", "BOM 缺少生效工艺路线", "BOM_ROUTING_MISSING"),
    BOM_OPERATION_NOT_FOUND("bom-operation-not-found", "BOM 消耗工序不存在", "BOM_OPERATION_NOT_FOUND"),
    BOM_LINE_DUPLICATE("bom-line-duplicate", "BOM 行号重复", "BOM_LINE_DUPLICATE"),
    BOM_CYCLE("bom-cycle", "BOM 循环引用", "BOM_CYCLE"),
    BOM_TOO_DEEP("bom-too-deep", "BOM 层级过深", "BOM_TOO_DEEP"),
    // ---- 工艺路线
    ROUTING_NOT_FOUND("routing-not-found", "工艺路线不存在", "ROUTING_NOT_FOUND"),
    ROUTING_NOT_EFFECTIVE("routing-not-effective", "无生效工艺路线", "ROUTING_NOT_EFFECTIVE"),
    ROUTING_PRODUCT_NOT_MAKE("routing-product-not-make", "采购件不能建立工艺路线", "ROUTING_PRODUCT_NOT_MAKE"),
    ROUTING_VERSION_EXISTS("routing-version-exists", "工艺路线版本已存在", "ROUTING_VERSION_EXISTS"),
    ROUTING_KEY_MISMATCH("routing-key-mismatch", "工艺路线路径与请求体不一致", "ROUTING_KEY_MISMATCH"),
    ROUTING_NOT_DRAFT("routing-not-draft", "工艺路线不是草稿", "ROUTING_NOT_DRAFT"),
    ROUTING_NOT_RELEASED("routing-not-released", "工艺路线未发布", "ROUTING_NOT_RELEASED"),
    ROUTING_RELEASE_CONFLICT("routing-release-conflict", "工艺路线发布冲突", "ROUTING_RELEASE_CONFLICT"),
    OPERATION_DUPLICATE("operation-duplicate", "工序号重复", "OPERATION_DUPLICATE"),
    EFFECTIVE_DATE_CONFLICT("effective-date-conflict", "生效日冲突", "EFFECTIVE_DATE_CONFLICT"),
    // ---- 工作中心 / 工位
    WORK_CENTER_NOT_FOUND("workcenter-not-found", "工作中心不存在", "WORK_CENTER_NOT_FOUND"),
    WORK_CENTER_EXISTS("workcenter-exists", "工作中心已存在", "WORK_CENTER_EXISTS"),
    STATION_NOT_FOUND("station-not-found", "工位不存在", "STATION_NOT_FOUND"),
    STATION_EXISTS("station-exists", "工位已存在", "STATION_EXISTS"),
    // ---- 引用检查
    /** 主数据仍被引用（WORK_ORDER / BOM / ROUTING），不能停用 / 删除：409。 */
    MASTERDATA_IN_USE("masterdata-in-use", "主数据被引用", "MASTERDATA_IN_USE"),
    WORKORDER_SERVICE_UNAVAILABLE("workorder-service-unavailable", "工单服务不可用", "WORKORDER_SERVICE_UNAVAILABLE"),
    // ---- 通用（与工单服务同名同文案）
    VALIDATION_ERROR("validation-error", "请求参数无效", "INVALID_ARGUMENT"),
    /** 401 由安全过滤链按 SecurityProblem 直接输出，登记于此以保证契约枚举完整。 */
    AUTHENTICATION_REQUIRED("authentication-required", "需要认证", "AUTHENTICATION_REQUIRED"),
    ACCESS_DENIED("access-denied", "没有访问权限", "ACCESS_DENIED"),
    INTERNAL_ERROR("internal-error", "内部错误", "INTERNAL_ERROR");

    private static final Map<String, MasterdataProblemType> BY_CODE =
        Arrays.stream(values()).collect(Collectors.toMap(MasterdataProblemType::code, Function.identity()));

    private final String type;
    private final String title;
    private final String code;
    MasterdataProblemType(String type, String title, String code) { this.type = type; this.title = title; this.code = code; }

    /** RFC 9457 type 标识（kebab-case 相对 URI，与工单服务写法一致）。 */
    String type() { return type; }
    /** 固定中文 title，同一 type 恒为同一 title。 */
    String title() { return title; }
    /** 历史业务码，作为 Problem 扩展字段 code 保留。 */
    String code() { return code; }
    /** 按业务码查找；未登记返回 null。 */
    static MasterdataProblemType ofCode(String code) { return BY_CODE.get(code); }
}
