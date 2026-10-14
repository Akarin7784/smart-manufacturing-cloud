package com.smartmanufacturing.workorder;

import feign.RequestTemplate;

/**
 * 质量服务调用的服务身份（#74）：关闭工单查询 NCR 时用工单服务自己的 client_credentials 令牌（scope=quality:read），
 * 不透传用户令牌，因此关闭只需要 workorder:write。只挂在 {@link QualityClient} 上（见 {@link QualityFeignConfiguration}）。
 */
class QualityServiceToken {
    static final String SCOPE = "quality:read";
    private final ServiceTokenProvider tokens;

    QualityServiceToken(ServiceTokenProvider tokens) { this.tokens = tokens; }

    /** 写入服务令牌，替换任何已有的 Authorization 头。不实现 RequestInterceptor，避免在父上下文被所有 Feign 客户端拾取。 */
    void apply(RequestTemplate template) {
        template.removeHeader("Authorization");
        template.header("Authorization", "Bearer " + tokens.token());
    }

    /** 质量服务返回 401 时丢弃缓存令牌，下次重新申请。 */
    void invalidate() { tokens.invalidate(); }
}
