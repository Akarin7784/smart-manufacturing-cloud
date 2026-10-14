package com.smartmanufacturing.workorder;
import feign.RequestInterceptor;
import org.slf4j.MDC;
import org.springframework.context.annotation.*;
/** 配置服务间请求头，将当前请求关联标识传递给设备服务。 */
@Configuration
public class FeignConfiguration {
    static final String QUALITY = "quality-service";
    /** 读取当前线程 MDC 中的关联标识并写入 Feign 请求头。 */
    @Bean RequestInterceptor requestIdInterceptor() {
        return template -> {
            String id = MDC.get("requestId");
            if (id != null) template.header("X-Request-Id", id);
            // 质量服务使用服务身份令牌（QualityServiceToken），不透传用户令牌（#74）
            if (template.feignTarget() != null && QUALITY.equals(template.feignTarget().name())) return;
            // 仅传递安全上下文中已校验的令牌，不信任任意外部身份请求头。
            var authentication = org.springframework.security.core.context.SecurityContextHolder.getContext().getAuthentication();
            if (authentication instanceof org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken jwt)
                template.header("Authorization", "Bearer " + jwt.getToken().getTokenValue());
        };
    }
}
