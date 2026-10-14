package com.smartmanufacturing.quality;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.smartmanufacturing.security.ManagementPortAccess;
import com.smartmanufacturing.security.SecurityClaims;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.web.SecurityFilterChain;
import java.io.IOException;

/**
 * 质检服务安全配置：只接受用户令牌（受众 manufacturing-api），按 permissions 声明做方法级授权
 * （查询 quality:read，写入 quality:write）。令牌由认证服务 RSA 私钥签名，通过 JWKS 获取公钥；不信任任何外部身份请求头。
 * <p>
 * 已通过 {@code manufacturing.security.resource-server=false} 关闭 cloud-common 的通用安全链：
 * 通用链的 401/403 输出 ApiResponse，而质检契约要求 problem+json（type=authentication-required / access-denied）。
 * 管理端口规则与库存服务一致：独立管理端口上匿名放开 Prometheus，业务端口上的 actuator 需 metrics:read。
 */
@Configuration(proxyBeanMethods = false)
@EnableMethodSecurity
public class QualitySecurityConfiguration {
    private final String jwks;
    private final String issuer;
    private final String audience;

    public QualitySecurityConfiguration(
            @Value("${manufacturing.security.jwk-set-uri:http://localhost:8083/auth/jwks}") String jwks,
            @Value("${manufacturing.security.issuer:http://localhost:8080}") String issuer,
            @Value("${manufacturing.security.audience:manufacturing-api}") String audience) {
        this.jwks = jwks; this.issuer = issuer; this.audience = audience;
    }

    /**
     * NCR 列表查询链（#74，优先于通用链）：只匹配 {@code GET /quality/ncrs}。除用户令牌外，还接受工单服务的服务令牌
     * （aud=quality-service、token_type=service、scope 含 quality:read），供关闭工单前统计未结案 NCR；
     * 服务令牌的 scope 映射为同名权限，仍由控制器上的 @PreAuthorize("hasAuthority('quality:read')") 把关。其他接口只认用户令牌。
     */
    @Bean
    @org.springframework.core.annotation.Order(0)
    SecurityFilterChain ncrQueryChain(HttpSecurity http, ObjectMapper mapper,
            @Value("${manufacturing.security.service-audience:quality-service}") String serviceAudience) throws Exception {
        http.securityMatcher(request -> "GET".equals(request.getMethod()) && "/quality/ncrs".equals(request.getRequestURI()))
            .csrf(csrf -> csrf.disable())
            .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .formLogin(form -> form.disable()).httpBasic(basic -> basic.disable()).logout(logout -> logout.disable())
            .exceptionHandling(errors -> errors
                .authenticationEntryPoint((req, res, e) -> write(mapper, req, res, 401, "请登录或重新获取令牌"))
                .accessDeniedHandler((req, res, e) -> write(mapper, req, res, 403, "没有访问权限")))
            .authorizeHttpRequests(rules -> rules.anyRequest().authenticated())
            .oauth2ResourceServer(resource -> resource
                .jwt(jwt -> jwt.decoder(userOrServiceDecoder(serviceAudience)).jwtAuthenticationConverter(userOrServiceConverter()))
                .authenticationEntryPoint((req, res, e) -> write(mapper, req, res, 401, "请登录或重新获取令牌")));
        return http.build();
    }

    /** 用户令牌（aud=manufacturing-api）或服务令牌（aud=quality-service 且 token_type=service）任一通过即可。 */
    JwtDecoder userOrServiceDecoder(String serviceAudience) {
        NimbusJwtDecoder decoder = NimbusJwtDecoder.withJwkSetUri(jwks).jwsAlgorithm(SignatureAlgorithm.RS256).build();
        decoder.setJwtValidator(userOrService(issuer, audience, serviceAudience));
        return decoder;
    }

    static org.springframework.security.oauth2.core.OAuth2TokenValidator<org.springframework.security.oauth2.jwt.Jwt> userOrService(
            String issuer, String userAudience, String serviceAudience) {
        var user = SecurityClaims.validators(issuer, userAudience);
        var service = SecurityClaims.validators(issuer, serviceAudience);
        return token -> {
            var u = user.validate(token);
            if (!u.hasErrors()) return u;
            var s = service.validate(token);
            if (s.hasErrors()) return u;
            return "service".equals(token.getClaimAsString("token_type")) ? s
                : org.springframework.security.oauth2.core.OAuth2TokenValidatorResult.failure(
                    new org.springframework.security.oauth2.core.OAuth2Error("invalid_token", "不是服务身份令牌（token_type 必须为 service）", null));
        };
    }

    /** 服务令牌：scope 原样作为权限（quality:read）；用户令牌：沿用 permissions 声明。 */
    static org.springframework.core.convert.converter.Converter<org.springframework.security.oauth2.jwt.Jwt, org.springframework.security.authentication.AbstractAuthenticationToken> userOrServiceConverter() {
        var users = SecurityClaims.authenticationConverter();
        var services = new org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter();
        var scopes = new org.springframework.security.oauth2.server.resource.authentication.JwtGrantedAuthoritiesConverter();
        scopes.setAuthorityPrefix("");
        services.setJwtGrantedAuthoritiesConverter(scopes);
        return jwt -> "service".equals(jwt.getClaimAsString("token_type")) ? services.convert(jwt) : users.convert(jwt);
    }

    /** 唯一过滤链：健康探针公开，管理端口上的 Prometheus 匿名可抓，其余 actuator 需 metrics:read，业务接口需认证后由 @PreAuthorize 细分。 */
    @Bean
    SecurityFilterChain qualityChain(HttpSecurity http, ObjectMapper mapper, Environment environment, JwtDecoder decoder) throws Exception {
        http.csrf(csrf -> csrf.disable())
            .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .formLogin(form -> form.disable()).httpBasic(basic -> basic.disable()).logout(logout -> logout.disable())
            .exceptionHandling(errors -> errors
                .authenticationEntryPoint((req, res, e) -> write(mapper, req, res, 401, "请登录或重新获取令牌"))
                .accessDeniedHandler((req, res, e) -> write(mapper, req, res, 403, "没有访问权限")))
            .authorizeHttpRequests(rules -> rules
                .requestMatchers("/actuator/health", "/actuator/health/**").permitAll()
                // 仅当请求落在独立管理端口（按本地套接字端口判断，不看 Host 头）时匿名放开 Prometheus 抓取。
                .requestMatchers(request -> ManagementPortAccess.PROMETHEUS_PATH.equals(request.getRequestURI())
                    && ManagementPortAccess.isManagementPort(environment, request.getLocalPort())).permitAll()
                .requestMatchers("/actuator/**").hasAuthority("metrics:read")
                .anyRequest().authenticated())
            .oauth2ResourceServer(resource -> resource
                .jwt(jwt -> jwt.decoder(decoder).jwtAuthenticationConverter(SecurityClaims.authenticationConverter()))
                .authenticationEntryPoint((req, res, e) -> write(mapper, req, res, 401, "请登录或重新获取令牌")));
        return http.build();
    }

    /** 按 JWKS 获取 RSA 公钥，固定 RS256，并校验签发者、有效期与受众（复用 cloud-security 的共享规则）。 */
    @Bean
    JwtDecoder qualityJwtDecoder() {
        NimbusJwtDecoder decoder = NimbusJwtDecoder.withJwkSetUri(jwks).jwsAlgorithm(SignatureAlgorithm.RS256).build();
        decoder.setJwtValidator(SecurityClaims.validators(issuer, audience));
        return decoder;
    }

    /** 过滤器层的错误不经过控制器异常处理，在此输出与业务错误一致的 problem+json；401 按 RFC 6750 附带 Bearer 质询。 */
    private static void write(ObjectMapper mapper, HttpServletRequest req, HttpServletResponse res, int status, String detail) throws IOException {
        res.setStatus(status);
        res.setContentType("application/problem+json;charset=UTF-8");
        if (status == 401) res.setHeader("WWW-Authenticate", "Bearer");
        // type 与 title 按状态取固定值（authentication-required / access-denied），detail 说明具体原因。
        SecurityProblemType problem = SecurityProblemType.of(status);
        mapper.writeValue(res.getWriter(), QualityProblemHandler.body(problem.value(), problem.title(), status, detail, req));
    }
}
