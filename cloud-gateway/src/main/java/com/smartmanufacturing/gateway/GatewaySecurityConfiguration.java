package com.smartmanufacturing.gateway;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.smartmanufacturing.api.ApiResponse;
import com.smartmanufacturing.security.SecurityClaims;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.*;
import org.springframework.http.*;
import org.springframework.security.config.web.server.ServerHttpSecurity;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.*;
import org.springframework.security.oauth2.server.resource.authentication.ReactiveJwtAuthenticationConverterAdapter;
import org.springframework.security.web.server.SecurityWebFilterChain;
import org.springframework.security.web.server.context.NoOpServerSecurityContextRepository;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

/** 在响应式网关校验 JWT 并执行接口权限检查；业务服务还会执行独立校验。 */
@Configuration
public class GatewaySecurityConfiguration {
    /** 公钥地址可使用内部认证服务地址，签发者始终保持对外统一地址。 */
    @Bean ReactiveJwtDecoder reactiveJwtDecoder(
            @Value("${manufacturing.security.jwk-set-uri:http://localhost:8083/auth/jwks}") String jwks,
            @Value("${manufacturing.security.issuer:http://localhost:8080}") String issuer,
            @Value("${manufacturing.security.audience:manufacturing-api}") String audience) {
        NimbusReactiveJwtDecoder decoder = NimbusReactiveJwtDecoder.withJwkSetUri(jwks).jwsAlgorithm(SignatureAlgorithm.RS256).build();
        decoder.setJwtValidator(SecurityClaims.validators(issuer, audience));
        return decoder;
    }

    /** 按请求方法和路径授权；未声明的新路由默认拒绝，避免新增接口意外开放。 */
    @Bean SecurityWebFilterChain gatewaySecurity(ServerHttpSecurity http, ObjectMapper mapper) {
        return http.csrf(csrf -> csrf.disable())
            .httpBasic(basic -> basic.disable()).formLogin(form -> form.disable()).logout(logout -> logout.disable())
            .securityContextRepository(NoOpServerSecurityContextRepository.getInstance())
            .authorizeExchange(rules -> rules
                .pathMatchers(HttpMethod.GET, "/actuator/health", "/actuator/health/**", "/api/auth/jwks").permitAll()
                .pathMatchers(HttpMethod.POST, "/api/auth/login").permitAll()
                .pathMatchers("/actuator/**").hasAuthority("metrics:read")
                .pathMatchers("/api/auth/users", "/api/auth/users/**").hasAuthority("user:manage")
                .pathMatchers(HttpMethod.GET, "/api/auth/me").authenticated()
                .pathMatchers("/api/devices/config").hasAuthority("config:read")
                .pathMatchers(HttpMethod.GET, "/api/devices", "/api/devices/**").hasAuthority("device:read")
                .pathMatchers(HttpMethod.GET, "/api/workorders", "/api/workorders/**").hasAuthority("workorder:read")
                .pathMatchers(HttpMethod.POST, "/api/workorders").hasAuthority("workorder:write")
                // 工单下达 / 冻结 / 恢复：写权限；下达时令牌透传主数据服务，还需 masterdata:read（由主数据服务校验）。
                .pathMatchers(HttpMethod.POST, "/api/workorders/*/release", "/api/workorders/*/hold", "/api/workorders/*/resume").hasAuthority("workorder:write")
                // 主数据：发布单独授权，必须排在通用写规则之前；服务内 @PreAuthorize 再次校验同样的权限。
                .pathMatchers(HttpMethod.GET, "/api/materials", "/api/materials/**", "/api/workcenters", "/api/workcenters/**",
                    "/api/boms", "/api/boms/**", "/api/routings", "/api/routings/**").hasAuthority("masterdata:read")
                .pathMatchers(HttpMethod.POST, "/api/boms/*/*/release", "/api/routings/*/*/release").hasAuthority("masterdata:release")
                .pathMatchers(HttpMethod.POST, "/api/materials", "/api/workcenters", "/api/workcenters/*/stations", "/api/boms", "/api/routings").hasAuthority("masterdata:write")
                .pathMatchers(HttpMethod.PUT, "/api/materials/*", "/api/boms/*/*", "/api/routings/*/*").hasAuthority("masterdata:write")
                .anyExchange().denyAll())
            .exceptionHandling(errors -> errors
                .authenticationEntryPoint((exchange, e) -> writeError(mapper, exchange, 401))
                .accessDeniedHandler((exchange, e) -> writeError(mapper, exchange, 403)))
            .oauth2ResourceServer(resource -> resource
                .jwt(jwt -> jwt.jwtAuthenticationConverter(new ReactiveJwtAuthenticationConverterAdapter(SecurityClaims.authenticationConverter())))
                .authenticationEntryPoint((exchange, e) -> writeError(mapper, exchange, 401)))
            .build();
    }

    /** 在过滤链返回统一错误体，不将验签内部异常暴露给调用者。 */
    private Mono<Void> writeError(ObjectMapper mapper, ServerWebExchange exchange, int status) {
        var response = exchange.getResponse();
        response.setStatusCode(HttpStatus.valueOf(status));
        response.getHeaders().setContentType(MediaType.APPLICATION_JSON);
        if (status == 401) response.getHeaders().set(HttpHeaders.WWW_AUTHENTICATE, "Bearer");
        try {
            byte[] body = mapper.writeValueAsBytes(ApiResponse.error(
                status == 401 ? "AUTHENTICATION_REQUIRED" : "ACCESS_DENIED",
                status == 401 ? "请登录或重新获取令牌" : "没有访问权限"));
            return response.writeWith(Mono.just(response.bufferFactory().wrap(body)));
        } catch (java.io.IOException e) {
            return Mono.error(e);
        }
    }
}
