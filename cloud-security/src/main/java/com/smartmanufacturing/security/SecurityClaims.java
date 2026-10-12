package com.smartmanufacturing.security;

import org.springframework.security.oauth2.core.*;
import org.springframework.security.oauth2.jwt.*;
import org.springframework.security.oauth2.server.resource.authentication.*;

/** 网关与业务服务共享权限声明映射及签发者、有效期、受众校验规则。 */
public final class SecurityClaims {
    private SecurityClaims() {}

    /** 映射签名令牌中的权限声明；不接受客户端自定义的角色请求头。 */
    public static JwtAuthenticationConverter authenticationConverter() {
        JwtGrantedAuthoritiesConverter authorities = new JwtGrantedAuthoritiesConverter();
        authorities.setAuthoritiesClaimName("permissions");
        authorities.setAuthorityPrefix("");
        JwtAuthenticationConverter converter = new JwtAuthenticationConverter();
        converter.setJwtGrantedAuthoritiesConverter(authorities);
        return converter;
    }

    /** 默认校验签发者和时间，再确认令牌明确签发给本系统。 */
    public static OAuth2TokenValidator<Jwt> validators(String issuer, String audience) {
        OAuth2TokenValidator<Jwt> target = token -> token.getAudience() != null && token.getAudience().contains(audience)
            ? OAuth2TokenValidatorResult.success()
            : OAuth2TokenValidatorResult.failure(new OAuth2Error("invalid_token", "令牌受众不匹配", null));
        // Spring 默认时间校验支持合理时钟偏差；额外要求令牌携带过期时间，避免无限期令牌。
        OAuth2TokenValidator<Jwt> expiryRequired = token -> token.getExpiresAt() != null
            ? OAuth2TokenValidatorResult.success()
            : OAuth2TokenValidatorResult.failure(new OAuth2Error("invalid_token", "令牌缺少过期时间", null));
        return new DelegatingOAuth2TokenValidator<>(JwtValidators.createDefaultWithIssuer(issuer), target, expiryRequired);
    }
}
