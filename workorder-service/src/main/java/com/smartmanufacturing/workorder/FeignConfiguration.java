package com.smartmanufacturing.workorder;
import feign.RequestInterceptor;
import org.slf4j.MDC;
import org.springframework.context.annotation.*;
/** 传播服务间关联标识、已验证 Bearer 令牌与当前全局事务上下文。 */
@Configuration
public class FeignConfiguration {
    /** 从当前线程安全及事务上下文构建请求头，禁止复制外部伪造身份。 */
    @Bean RequestInterceptor requestIdInterceptor() {
        return template -> {
            // 只传播服务端启动的全局事务，不从外部请求头直接复制 XID。
            String xid = org.apache.seata.core.context.RootContext.getXID();
            if (xid != null) template.header(org.apache.seata.core.context.RootContext.KEY_XID, xid);
            String id = MDC.get("requestId");
            if (id != null) template.header("X-Request-Id", id);
            // 仅传递安全上下文中已校验的令牌，不信任任意外部身份请求头。
            var authentication = org.springframework.security.core.context.SecurityContextHolder.getContext().getAuthentication();
            if (authentication instanceof org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken jwt)
                template.header("Authorization", "Bearer " + jwt.getToken().getTokenValue());
        };
    }
}
