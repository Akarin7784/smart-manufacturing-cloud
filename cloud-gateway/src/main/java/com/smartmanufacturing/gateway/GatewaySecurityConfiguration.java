package com.smartmanufacturing.gateway;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.smartmanufacturing.security.ManagementPortAccess;
import com.smartmanufacturing.security.SecurityClaims;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.*;
import org.springframework.core.env.Environment;
import org.springframework.http.*;
import org.springframework.security.config.web.server.ServerHttpSecurity;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.*;
import org.springframework.security.oauth2.server.resource.authentication.ReactiveJwtAuthenticationConverterAdapter;
import org.springframework.security.web.server.SecurityWebFilterChain;
import org.springframework.security.web.server.context.NoOpServerSecurityContextRepository;
import org.springframework.security.web.server.util.matcher.ServerWebExchangeMatcher.MatchResult;
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
    @Bean SecurityWebFilterChain gatewaySecurity(ServerHttpSecurity http, ObjectMapper mapper, Environment environment) {
        return http.csrf(csrf -> csrf.disable())
            .httpBasic(basic -> basic.disable()).formLogin(form -> form.disable()).logout(logout -> logout.disable())
            .securityContextRepository(NoOpServerSecurityContextRepository.getInstance())
            .authorizeExchange(rules -> rules
                .pathMatchers(HttpMethod.GET, "/actuator/health", "/actuator/health/**", "/api/auth/jwks").permitAll()
                // cloud profile 下健康探针的附加路径（业务端口上的 /livez、/readyz），供宿主机通过 8080 确认网关存活。
                .pathMatchers(HttpMethod.GET, "/livez", "/readyz").permitAll()
                // 仅在独立管理端口（不对宿主机发布）上匿名开放 Prometheus 抓取；8080 上的 actuator 仍需 metrics:read。
                .matchers(exchange -> isPrometheusOnManagementPort(exchange, environment)
                    ? MatchResult.match()
                    : MatchResult.notMatch()).permitAll()
                .pathMatchers(HttpMethod.POST, "/api/auth/login").permitAll()
                // 服务令牌端点由认证服务按 RFC 6749 校验客户端凭据，网关不要求 JWT；
                // 演示环境服务间调用经 Nacos 直连 auth-service，此网关入口主要供冒烟与外部集成使用，生产应叠加 Sentinel 限流。
                .pathMatchers(HttpMethod.POST, "/api/auth/oauth2/token").permitAll()
                .pathMatchers("/actuator/**").hasAuthority("metrics:read")
                .pathMatchers("/api/auth/users", "/api/auth/users/**").hasAuthority("user:manage")
                .pathMatchers(HttpMethod.GET, "/api/auth/me").authenticated()
                // 派工用操作工下拉（M1）：计划员 / 管理员；兼容期与派工接口一致，workorder:write 亦可
                .pathMatchers(HttpMethod.GET, "/api/auth/operators").hasAnyAuthority("execution:dispatch", "workorder:write")
                .pathMatchers("/api/devices/config").hasAuthority("config:read")
                .pathMatchers(HttpMethod.GET, "/api/devices", "/api/devices/**").hasAuthority("device:read")
                .pathMatchers(HttpMethod.GET, "/api/workorders", "/api/workorders/**", "/api/operation-tasks", "/api/operation-tasks/**").hasAuthority("workorder:read")
                // M1 派工 / 改派 / 取消派工：execution:dispatch，兼容期 workorder:write 亦可（服务内 @PreAuthorize 同样规则）
                .pathMatchers(HttpMethod.POST, "/api/workorders/*/operations/*/dispatch", "/api/workorders/*/operations/*/reassign",
                    "/api/workorders/*/operations/*/cancel-dispatch").hasAnyAuthority("execution:dispatch", "workorder:write")
                // M1 开工 / 暂停 / 恢复与报工、冲销：execution:report，兼容期 workorder:write 亦可
                .pathMatchers(HttpMethod.POST, "/api/workorders/*/operations/*/start", "/api/workorders/*/operations/*/pause",
                    "/api/workorders/*/operations/*/resume", "/api/workorders/*/operations/*/reports",
                    "/api/workorders/*/reports/*/reversal").hasAnyAuthority("execution:report", "workorder:write")
                .pathMatchers(HttpMethod.POST, "/api/workorders").hasAuthority("workorder:write")
                // 工单下达 / 冻结 / 恢复：写权限；下达时令牌透传主数据服务，还需 masterdata:read（由主数据服务校验）。
                .pathMatchers(HttpMethod.POST, "/api/workorders/*/release", "/api/workorders/*/hold", "/api/workorders/*/resume").hasAuthority("workorder:write")
                // 工序报工、冲销、手工完工与取消
                .pathMatchers(HttpMethod.POST, "/api/workorders/*/complete", "/api/workorders/*/cancel").hasAuthority("workorder:write")
                // PR2：关闭（令牌透传质量服务，还需 quality:read，由质量服务校验）与撤回下达
                .pathMatchers(HttpMethod.POST, "/api/workorders/*/close", "/api/workorders/*/withdraw-release").hasAuthority("workorder:write")
                // 主数据：发布单独授权，必须排在通用写规则之前；服务内 @PreAuthorize 再次校验同样的权限。
                .pathMatchers(HttpMethod.GET, "/api/materials", "/api/materials/**", "/api/workcenters", "/api/workcenters/**",
                    "/api/boms", "/api/boms/**", "/api/routings", "/api/routings/**").hasAuthority("masterdata:read")
                .pathMatchers(HttpMethod.POST, "/api/boms/*/*/release", "/api/routings/*/*/release",
                    "/api/boms/*/*/retire", "/api/routings/*/*/retire").hasAuthority("masterdata:release")
                .pathMatchers(HttpMethod.POST, "/api/materials", "/api/workcenters", "/api/workcenters/*/stations", "/api/boms", "/api/routings").hasAuthority("masterdata:write")
                .pathMatchers(HttpMethod.PUT, "/api/materials/*", "/api/boms/*/*", "/api/routings/*/*",
                    "/api/workcenters/*", "/api/workcenters/*/stations/*").hasAuthority("masterdata:write")
                // PR2：软删除工作中心 / 工位、删除 BOM / 工艺路线草稿
                .pathMatchers(HttpMethod.DELETE, "/api/workcenters/*", "/api/workcenters/*/stations/*", "/api/boms/*/*", "/api/routings/*/*").hasAuthority("masterdata:write")
                // 库存：预留、QC-HOLD 等服务间内部接口已由 InternalPathDenyFilter 在认证前统一返回 404，不会到达这里。
                .pathMatchers(HttpMethod.GET, "/api/inventory/**").hasAuthority("inventory:read")
                .pathMatchers(HttpMethod.POST, "/api/inventory/locations", "/api/inventory/inbound", "/api/inventory/outbound").hasAuthority("inventory:write")
                // 质检：查询需 quality:read；评审、处置、关闭需 quality:write（与 quality-openapi.yaml 一致，服务内 @PreAuthorize 再校验）。
                .pathMatchers(HttpMethod.GET, "/api/quality/**").hasAuthority("quality:read")
                .pathMatchers(HttpMethod.POST, "/api/quality/ncrs/*/review", "/api/quality/ncrs/*/dispositions", "/api/quality/ncrs/*/close").hasAuthority("quality:write")
                .anyExchange().denyAll())
            .exceptionHandling(errors -> errors
                .authenticationEntryPoint((exchange, e) -> writeError(mapper, exchange, 401))
                .accessDeniedHandler((exchange, e) -> writeError(mapper, exchange, 403)))
            .oauth2ResourceServer(resource -> resource
                .jwt(jwt -> jwt.jwtAuthenticationConverter(new ReactiveJwtAuthenticationConverterAdapter(SecurityClaims.authenticationConverter())))
                .authenticationEntryPoint((exchange, e) -> writeError(mapper, exchange, 401)))
            .build();
    }

    /** 在过滤链返回 problem+json 错误体，不将验签内部异常暴露给调用者。 */
    private Mono<Void> writeError(ObjectMapper mapper, ServerWebExchange exchange, int status) {
        return (status == 401 ? GatewayProblem.AUTHENTICATION_REQUIRED : GatewayProblem.ACCESS_DENIED).write(mapper, exchange);
    }

    /** 判定请求是否为落在独立管理端口上的 Prometheus 抓取；端口取自本地套接字地址，无法通过请求头伪造。 */
    static boolean isPrometheusOnManagementPort(ServerWebExchange exchange, Environment environment) {
        var local = exchange.getRequest().getLocalAddress();
        return local != null && ManagementPortAccess.PROMETHEUS_PATH.equals(exchange.getRequest().getPath().value())
            && ManagementPortAccess.isManagementPort(environment, local.getPort());
    }
}
