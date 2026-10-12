package com.smartmanufacturing.common;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.smartmanufacturing.security.ManagementPortAccess;
import com.smartmanufacturing.security.SecurityClaims;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.*;
import org.springframework.core.env.Environment;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.*;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.util.matcher.RequestMatcher;

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
    @Bean SecurityFilterChain resourceSecurity(HttpSecurity http, ObjectMapper mapper, Environment environment) throws Exception {
        http.csrf(csrf -> csrf.disable())
            .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .formLogin(form -> form.disable()).httpBasic(basic -> basic.disable()).logout(logout -> logout.disable())
            .authorizeHttpRequests(rules -> rules
                .requestMatchers("/actuator/health", "/actuator/health/**").permitAll()
                // 仅在独立管理端口（内网）上匿名开放 Prometheus 抓取；业务端口上的 actuator 仍需 metrics:read。
                .requestMatchers(prometheusOnManagementPort(environment)).permitAll()
                .requestMatchers("/actuator/**").hasAuthority("metrics:read")
                .anyRequest().authenticated())
            .exceptionHandling(errors -> errors
                .authenticationEntryPoint((req, res, e) -> SecurityProblem.AUTHENTICATION_REQUIRED.write(mapper, req, res))
                .accessDeniedHandler((req, res, e) -> SecurityProblem.ACCESS_DENIED.write(mapper, req, res)))
            .oauth2ResourceServer(resource -> resource
                .jwt(jwt -> jwt.jwtAuthenticationConverter(SecurityClaims.authenticationConverter()))
                .authenticationEntryPoint((req, res, e) -> SecurityProblem.AUTHENTICATION_REQUIRED.write(mapper, req, res)));
        return http.build();
    }

    /** 匹配“落在独立管理端口上的 /actuator/prometheus 请求”，供认证服务的安全链复用。 */
    public static RequestMatcher prometheusOnManagementPort(Environment environment) {
        return request -> ManagementPortAccess.PROMETHEUS_PATH.equals(request.getRequestURI())
            && ManagementPortAccess.isManagementPort(environment, request.getLocalPort());
    }
}
