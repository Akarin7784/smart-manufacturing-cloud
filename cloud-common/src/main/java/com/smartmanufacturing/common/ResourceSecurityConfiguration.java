package com.smartmanufacturing.common;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.smartmanufacturing.api.ApiResponse;
import com.smartmanufacturing.security.SecurityClaims;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.*;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.*;
import org.springframework.security.web.SecurityFilterChain;

/** 业务服务独立验签；即使请求绕过网关，也必须通过身份校验和方法权限控制。 */
@Configuration
@EnableMethodSecurity
@ConditionalOnProperty(name = "manufacturing.security.resource-server", havingValue = "true", matchIfMissing = true)
public class ResourceSecurityConfiguration {
    /** 按 JWKS 获取 RSA 公钥，固定签名算法并校验签发者、有效期与受众。 */
    @Bean JwtDecoder jwtDecoder(
            @Value("${manufacturing.security.jwk-set-uri:http://localhost:8083/auth/jwks}") String jwks,
            @Value("${manufacturing.security.issuer:http://localhost:8080}") String issuer,
            @Value("${manufacturing.security.audience:manufacturing-api}") String audience) {
        NimbusJwtDecoder decoder = NimbusJwtDecoder.withJwkSetUri(jwks).jwsAlgorithm(SignatureAlgorithm.RS256).build();
        decoder.setJwtValidator(SecurityClaims.validators(issuer, audience));
        return decoder;
    }

    /** API 只接受显式 Bearer 令牌，不使用会话、表单登录或 Cookie 认证。 */
    @Bean SecurityFilterChain resourceSecurity(HttpSecurity http, ObjectMapper mapper) throws Exception {
        http.csrf(csrf -> csrf.disable())
            .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .formLogin(form -> form.disable()).httpBasic(basic -> basic.disable()).logout(logout -> logout.disable())
            .authorizeHttpRequests(rules -> rules
                .requestMatchers("/actuator/health", "/actuator/health/**").permitAll()
                .requestMatchers("/actuator/**").hasAuthority("metrics:read")
                .anyRequest().authenticated())
            .exceptionHandling(errors -> errors
                .authenticationEntryPoint((req, res, e) -> writeError(mapper, res, 401, "AUTHENTICATION_REQUIRED", "请登录或重新获取令牌"))
                .accessDeniedHandler((req, res, e) -> writeError(mapper, res, 403, "ACCESS_DENIED", "没有访问权限")))
            .oauth2ResourceServer(resource -> resource
                .jwt(jwt -> jwt.jwtAuthenticationConverter(SecurityClaims.authenticationConverter()))
                .authenticationEntryPoint((req, res, e) -> writeError(mapper, res, 401, "AUTHENTICATION_REQUIRED", "请登录或重新获取令牌")));
        return http.build();
    }

    /** 安全过滤器中的异常不经过控制器异常处理，需在此统一输出 JSON。 */
    public static void writeError(ObjectMapper mapper, HttpServletResponse response, int status, String code, String message)
            throws IOException {
        response.setStatus(status);
        response.setContentType("application/json;charset=UTF-8");
        if (status == 401) response.setHeader("WWW-Authenticate", "Bearer");
        mapper.writeValue(response.getWriter(), ApiResponse.error(code, message));
    }
}
