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
}
