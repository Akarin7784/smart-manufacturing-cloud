package com.smartmanufacturing.inventory;

import org.springframework.http.HttpStatus;

/**
 * 本服务过滤器层与方法级授权产生的 401/403 错误类型（RFC 9457 problem+json）。
 * <p>
 * type 采用相对引用，取值与标题和 cloud-common 共用安全配置（#48 的 SecurityProblem）保持一致：
 * 同一 type 的 title 固定为中文短语，调用方只按 type 分支，不解析 title/detail。
 * 网关拦截的同类错误为 gateway-* 前缀，据此区分是网关还是服务本身拒绝。
 * 这两种类型不属于业务错误枚举 ProblemType，契约中单独列出。
 * <p>
 * 临时实现：#48 合入后，本枚举将被 cloud-common 的 SecurityProblem 替换并删除。
 */
public enum SecurityProblemType {
    /** 未携带令牌，或令牌签名、签发者、受众、有效期校验失败；响应附带 WWW-Authenticate: Bearer。 */
    AUTHENTICATION_REQUIRED("authentication-required", HttpStatus.UNAUTHORIZED, "需要认证"),
    /** 身份有效但缺少接口所需权限或 scope（过滤链规则或方法级 @PreAuthorize）。 */
    ACCESS_DENIED("access-denied", HttpStatus.FORBIDDEN, "没有访问权限");

    private final String value;
    private final HttpStatus status;
    private final String title;

    SecurityProblemType(String value, HttpStatus status, String title) {
        this.value = value; this.status = status; this.title = title;
    }

    /** 契约中的 type 取值。 */
    public String value() { return value; }
    /** 对应的 HTTP 状态。 */
    public HttpStatus status() { return status; }
    /** 固定的中文标题。 */
    public String title() { return title; }

    /** 按 HTTP 状态取类型：401 为需要认证，其余（403）为没有访问权限。 */
    static SecurityProblemType of(int status) {
        return status == HttpStatus.UNAUTHORIZED.value() ? AUTHENTICATION_REQUIRED : ACCESS_DENIED;
    }
}
