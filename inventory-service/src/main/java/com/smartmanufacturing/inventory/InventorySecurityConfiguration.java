package com.smartmanufacturing.inventory;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.smartmanufacturing.security.ManagementPortAccess;
import com.smartmanufacturing.security.SecurityClaims;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.core.env.Environment;
import org.springframework.security.authorization.AuthorizationDecision;
import org.springframework.security.authorization.AuthorizationManager;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.access.intercept.RequestAuthorizationContext;
import java.io.IOException;
import java.util.function.Supplier;

/**
 * 库存服务安全配置：按路径拆成两条过滤链，分别校验两类令牌。
 * <ul>
 *   <li><b>预留接口</b>（/inventory/reservations/**）：只接受工单服务以 OAuth2 客户端凭证获取的服务身份令牌，
 *       令牌受众必须为 {@code inventory-service}（解码阶段校验，不符返回 401），且 scope 含 {@code inventory:reserve}（不足返回 403）。</li>
 *   <li><b>质检隔离接口</b>（/inventory/qc-hold/**）：只接受质检服务的服务身份令牌，受众同样为 {@code inventory-service}，
 *       scope 必须含 {@code inventory:qc-hold}（不足 403）；两种 scope 互不通用。</li>
 *   <li><b>其他接口</b>（库位、批次、出入库、查询）：接受用户令牌（受众 manufacturing-api），按 permissions 声明做方法级授权。</li>
 * </ul>
 * 两类令牌均由认证服务用同一 RSA 私钥签名，通过 JWKS 获取公钥；不信任任何外部身份请求头。
 * 已通过 {@code manufacturing.security.resource-server=false} 关闭 cloud-common 的通用安全链，避免两套链同时生效。
 */
@Configuration(proxyBeanMethods = false)
@EnableMethodSecurity
public class InventorySecurityConfiguration {
    /** 预留接口所需的 scope（Spring 默认以 SCOPE_ 前缀映射 scope 声明）。 */
    static final String RESERVE_AUTHORITY = "SCOPE_inventory:reserve";
    /** 质检隔离接口所需的 scope（仅质检服务客户端持有）。 */
    static final String QC_HOLD_AUTHORITY = "SCOPE_inventory:qc-hold";

    private final String jwks;
    private final String issuer;
    private final String userAudience;
    private final String serviceAudience;

    public InventorySecurityConfiguration(
            @Value("${manufacturing.security.jwk-set-uri:http://localhost:8083/auth/jwks}") String jwks,
            @Value("${manufacturing.security.issuer:http://localhost:8080}") String issuer,
            @Value("${manufacturing.security.audience:manufacturing-api}") String userAudience,
            @Value("${manufacturing.security.service-audience:inventory-service}") String serviceAudience) {
        this.jwks = jwks; this.issuer = issuer; this.userAudience = userAudience; this.serviceAudience = serviceAudience;
    }

    /**
     * 预留接口过滤链（优先匹配）。
     * 解码器只认 aud=inventory-service；授权阶段再次核对受众与 scope，作为纵深防御（也便于测试中直接验证规则）。
     */
    @Bean @Order(1)
    SecurityFilterChain reservationChain(HttpSecurity http, ObjectMapper mapper) throws Exception {
        common(http, mapper)
            .securityMatcher("/inventory/reservations", "/inventory/reservations/**")
            .authorizeHttpRequests(rules -> rules.anyRequest().access(serviceTokenRule()))
            .oauth2ResourceServer(resource -> resource
                .jwt(jwt -> jwt.decoder(serviceDecoder()).jwtAuthenticationConverter(new JwtAuthenticationConverter()))
                .authenticationEntryPoint((req, res, e) -> write(mapper, req, res, 401, "请提供有效的服务身份令牌")));
        return http.build();
    }

    /**
     * 质检隔离接口过滤链（与预留链同级优先）。用户令牌（aud=manufacturing-api）在解码阶段即被拒绝（401），
     * 预留令牌 scope 不符返回 403。
     */
    @Bean @Order(0)
    SecurityFilterChain qcHoldChain(HttpSecurity http, ObjectMapper mapper) throws Exception {
        common(http, mapper)
            .securityMatcher("/inventory/qc-hold", "/inventory/qc-hold/**")
            .authorizeHttpRequests(rules -> rules.anyRequest().access(serviceTokenRule(QC_HOLD_AUTHORITY)))
            .oauth2ResourceServer(resource -> resource
                .jwt(jwt -> jwt.decoder(serviceDecoder()).jwtAuthenticationConverter(new JwtAuthenticationConverter()))
                .authenticationEntryPoint((req, res, e) -> write(mapper, req, res, 401, "请提供有效的服务身份令牌")));
        return http.build();
    }

    /** 其他接口过滤链：健康探针公开，管理端口上的 Prometheus 匿名可抓，其余指标需 metrics:read，其余需认证后由 @PreAuthorize 细分 inventory:read / inventory:write。 */
    @Bean @Order(2)
    SecurityFilterChain userChain(HttpSecurity http, ObjectMapper mapper, Environment environment) throws Exception {
        common(http, mapper)
            .authorizeHttpRequests(rules -> rules
                .requestMatchers("/actuator/health", "/actuator/health/**").permitAll()
                // 仅当请求落在独立管理端口（按本地套接字端口判断，不看 Host 头）时匿名放开 Prometheus 抓取；
                // 业务端口上的 /actuator/** 仍需 metrics:read，与 #21 中其他服务口径一致。
                .requestMatchers(request -> ManagementPortAccess.PROMETHEUS_PATH.equals(request.getRequestURI())
                    && ManagementPortAccess.isManagementPort(environment, request.getLocalPort())).permitAll()
                .requestMatchers("/actuator/**").hasAuthority("metrics:read")
                .anyRequest().authenticated())
            .oauth2ResourceServer(resource -> resource
                .jwt(jwt -> jwt.decoder(decoder(userAudience)).jwtAuthenticationConverter(SecurityClaims.authenticationConverter()))
                .authenticationEntryPoint((req, res, e) -> write(mapper, req, res, 401, "请登录或重新获取令牌")));
        return http.build();
    }

    /** 两条链共用的无状态设置：只接受 Bearer 令牌，不使用会话、表单登录或 Cookie。 */
    private static HttpSecurity common(HttpSecurity http, ObjectMapper mapper) throws Exception {
        return http.csrf(csrf -> csrf.disable())
            .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .formLogin(form -> form.disable()).httpBasic(basic -> basic.disable()).logout(logout -> logout.disable())
            .exceptionHandling(errors -> errors
                .authenticationEntryPoint((req, res, e) -> write(mapper, req, res, 401, "请登录或重新获取令牌"))
                .accessDeniedHandler((req, res, e) -> write(mapper, req, res, 403, "没有访问权限")));
    }

    /** 服务令牌授权规则：受众含 inventory-service 且具备 inventory:reserve。 */
    AuthorizationManager<RequestAuthorizationContext> serviceTokenRule() {
        return serviceTokenRule(RESERVE_AUTHORITY);
    }

    /** 服务令牌授权规则（按所需 scope 参数化）：受众含 inventory-service 且具备指定 scope 权限。 */
    AuthorizationManager<RequestAuthorizationContext> serviceTokenRule(String requiredAuthority) {
        return (Supplier<Authentication> auth, RequestAuthorizationContext ctx) -> {
            Authentication a = auth.get();
            boolean ok = a instanceof JwtAuthenticationToken jwt
                && jwt.getToken().getAudience() != null && jwt.getToken().getAudience().contains(serviceAudience)
                && a.getAuthorities().stream().anyMatch(g -> requiredAuthority.equals(g.getAuthority()));
            return new AuthorizationDecision(ok);
        };
    }

    /**
     * 预留链与 qc-hold 链共用的服务令牌解码器：在共享规则（签发者、有效期、aud=inventory-service）之上，
     * 额外要求 {@code token_type=service}（architecture.md 约定，#44）。缺少或取值错误在解码阶段失败，返回 401，
     * 防止误配了 inventory-service 受众的其他令牌仅凭 aud 与 scope 通过。
     */
    JwtDecoder serviceDecoder() {
        NimbusJwtDecoder decoder = (NimbusJwtDecoder) decoder(serviceAudience);
        OAuth2TokenValidator<Jwt> serviceType = token -> "service".equals(token.getClaimAsString("token_type"))
            ? OAuth2TokenValidatorResult.success()
            : OAuth2TokenValidatorResult.failure(new OAuth2Error("invalid_token", "不是服务身份令牌（token_type 必须为 service）", null));
        decoder.setJwtValidator(new DelegatingOAuth2TokenValidator<>(SecurityClaims.validators(issuer, serviceAudience), serviceType));
        return decoder;
    }

    /** 按 JWKS 获取 RSA 公钥，固定 RS256，并校验签发者、有效期与指定受众（复用 cloud-security 的共享规则）。 */
    private JwtDecoder decoder(String audience) {
        NimbusJwtDecoder decoder = NimbusJwtDecoder.withJwkSetUri(jwks).jwsAlgorithm(SignatureAlgorithm.RS256).build();
        decoder.setJwtValidator(SecurityClaims.validators(issuer, audience));
        return decoder;
    }

    /** 过滤器层的错误不经过控制器异常处理，在此输出与业务错误一致的 problem+json。 */
    private static void write(ObjectMapper mapper, HttpServletRequest req, HttpServletResponse res, int status, String detail) throws IOException {
        res.setStatus(status);
        res.setContentType("application/problem+json;charset=UTF-8");
        if (status == 401) res.setHeader("WWW-Authenticate", "Bearer");
        mapper.writeValue(res.getWriter(), InventoryProblemHandler.body("about:blank", status == 401 ? "未认证" : "没有访问权限", status, detail, req));
    }
}
