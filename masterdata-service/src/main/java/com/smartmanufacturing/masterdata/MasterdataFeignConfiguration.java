package com.smartmanufacturing.masterdata;

import feign.RequestInterceptor;
import org.slf4j.MDC;
import org.springframework.cloud.openfeign.EnableFeignClients;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

/** 启用 Feign 并透传关联标识与已验证的用户令牌（不信任任意外部身份请求头）。 */
@Configuration
@EnableFeignClients(basePackageClasses = WorkOrderReferenceClient.class)
public class MasterdataFeignConfiguration {
    @Bean RequestInterceptor masterdataTokenRelay() {
        return template -> {
            String id = MDC.get("requestId");
            if (id != null) template.header("X-Request-Id", id);
            if (SecurityContextHolder.getContext().getAuthentication() instanceof JwtAuthenticationToken jwt)
                template.header("Authorization", "Bearer " + jwt.getToken().getTokenValue());
        };
    }
}
