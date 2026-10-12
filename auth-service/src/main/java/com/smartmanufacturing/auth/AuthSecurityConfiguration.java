package com.smartmanufacturing.auth;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nimbusds.jose.jwk.RSAKey;
import com.smartmanufacturing.common.ResourceSecurityConfiguration;
import com.smartmanufacturing.security.SecurityClaims;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.*;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.jwt.*;
import org.springframework.security.web.SecurityFilterChain;

/** 认证服务自身也要求 JWT；只有登录、公钥及健康探针允许匿名访问。 */
@Configuration
@EnableMethodSecurity
public class AuthSecurityConfiguration {
    /** BCrypt 以随机盐保存密码散列，不保存可逆加密或明文密码。 */
    @Bean PasswordEncoder passwordEncoder() { return new BCryptPasswordEncoder(12); }

    /** 使用本服务公钥验签，避免认证服务通过网络回调自己获取密钥。 */
    @Bean JwtDecoder authJwtDecoder(RSAKey key,
            @Value("${manufacturing.security.issuer:http://localhost:8080}") String issuer,
            @Value("${manufacturing.security.audience:manufacturing-api}") String audience) throws Exception {
        NimbusJwtDecoder decoder = NimbusJwtDecoder.withPublicKey(key.toRSAPublicKey()).build();
        decoder.setJwtValidator(SecurityClaims.validators(issuer, audience));
        return decoder;
    }

    /** 登录采用 JSON 密码请求，其余身份信息只从经过验签的 Bearer 令牌读取。 */
    @Bean SecurityFilterChain authSecurity(HttpSecurity http, ObjectMapper mapper) throws Exception {
        http.csrf(csrf -> csrf.disable())
            .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .formLogin(form -> form.disable()).httpBasic(basic -> basic.disable()).logout(logout -> logout.disable())
            .authorizeHttpRequests(rules -> rules
                .requestMatchers(HttpMethod.POST, "/auth/login").permitAll()
                .requestMatchers(HttpMethod.GET, "/auth/jwks", "/actuator/health", "/actuator/health/**").permitAll()
                .requestMatchers("/actuator/**").hasAuthority("metrics:read")
                .requestMatchers("/auth/me", "/auth/users", "/auth/users/**").authenticated()
                .anyRequest().denyAll())
            .exceptionHandling(errors -> errors
                .authenticationEntryPoint((req, res, e) -> ResourceSecurityConfiguration.writeError(mapper, res, 401, "AUTHENTICATION_REQUIRED", "请登录或重新获取令牌"))
                .accessDeniedHandler((req, res, e) -> ResourceSecurityConfiguration.writeError(mapper, res, 403, "ACCESS_DENIED", "没有访问权限")))
            .oauth2ResourceServer(resource -> resource
                .jwt(jwt -> jwt.jwtAuthenticationConverter(SecurityClaims.authenticationConverter()))
                .authenticationEntryPoint((req, res, e) -> ResourceSecurityConfiguration.writeError(mapper, res, 401, "AUTHENTICATION_REQUIRED", "请登录或重新获取令牌")));
        return http.build();
    }
}
