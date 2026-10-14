package com.smartmanufacturing.masterdata;
import com.smartmanufacturing.common.BusinessException;
import org.springframework.http.HttpStatus;

/** 主数据业务错误的统一构造，集中维护错误码与 HTTP 状态，保证对外错误码稳定。 */
final class Errors {
    private Errors() { }
    /** 资源不存在：404。 */
    static BusinessException notFound(String code, String message) { return new BusinessException(code, message, HttpStatus.NOT_FOUND); }
    /** 状态或并发冲突（重复创建、非草稿修改、乐观锁失败）：409。 */
    static BusinessException conflict(String code, String message) { return new BusinessException(code, message, HttpStatus.CONFLICT); }
    /** 请求格式正确但违反业务规则（循环 BOM、引用不存在的工序等）：422。 */
    static BusinessException rule(String code, String message) { return new BusinessException(code, message, HttpStatus.UNPROCESSABLE_ENTITY); }

    /**
     * 主数据仍被引用，不能停用 / 删除：409，problem+json 顶层 type = masterdata-in-use（#71 起不再放在 data.type），
     * data.referenceType 为 WORK_ORDER / BOM / ROUTING，data.references 列出前若干个引用方。
     */
    static BusinessException inUse(String referenceType, String message, java.util.List<String> references) {
        var data = new java.util.LinkedHashMap<String, Object>();
        data.put("referenceType", referenceType);
        data.put("references", references);
        return new BusinessException("MASTERDATA_IN_USE", message, HttpStatus.CONFLICT, data);
    }
    /** 引用检查依赖的工单服务不可用：503，不放行停用 / 删除。 */
    static BusinessException workOrderUnavailable(String message) {
        return new BusinessException("WORKORDER_SERVICE_UNAVAILABLE", message, HttpStatus.SERVICE_UNAVAILABLE);
    }
}
