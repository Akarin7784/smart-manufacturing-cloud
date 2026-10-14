package com.smartmanufacturing.quality;

import org.junit.jupiter.api.Test;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** #74：NCR 列表查询链接受用户令牌或工单服务的服务令牌（aud=quality-service、token_type=service），服务令牌 scope 映射为同名权限。 */
class NcrServiceTokenTest {
    private static final String ISSUER = "http://localhost:8080";
    private final org.springframework.security.oauth2.core.OAuth2TokenValidator<Jwt> validator = QualitySecurityConfiguration.userOrService(ISSUER, "manufacturing-api", "quality-service");

    private static Jwt jwt(String aud, String tokenType, String scope, List<String> permissions) {
        Jwt.Builder b = Jwt.withTokenValue("t").header("alg", "RS256").issuer(ISSUER).audience(List.of(aud))
            .issuedAt(Instant.now().minusSeconds(5)).notBefore(Instant.now().minusSeconds(5)).expiresAt(Instant.now().plusSeconds(300)).subject("s");
        if (tokenType != null) b.claim("token_type", tokenType);
        if (scope != null) b.claim("scope", scope);
        if (permissions != null) b.claim("permissions", permissions);
        return b.build();
    }

    @Test void acceptsUserAndServiceTokens() {
        assertThat(validator.validate(jwt("manufacturing-api", null, null, List.of("quality:read"))).hasErrors()).isFalse();
        assertThat(validator.validate(jwt("quality-service", "service", "quality:read", null)).hasErrors()).isFalse();
    }

    @Test void rejectsWrongAudienceOrNonServiceToken() {
        assertThat(validator.validate(jwt("inventory-service", "service", "inventory:reserve", null)).hasErrors()).isTrue();
        assertThat(validator.validate(jwt("quality-service", null, "quality:read", null)).hasErrors()).isTrue();
        assertThat(validator.validate(jwt("quality-service", "user", "quality:read", null)).hasErrors()).isTrue();
    }

    @Test void serviceScopeMapsToPermission() {
        var converter = QualitySecurityConfiguration.userOrServiceConverter();
        assertThat(converter.convert(jwt("quality-service", "service", "quality:read", null)).getAuthorities())
            .extracting(GrantedAuthority::getAuthority).containsExactly("quality:read");
        assertThat(converter.convert(jwt("manufacturing-api", null, null, List.of("quality:read", "quality:write"))).getAuthorities())
            .extracting(GrantedAuthority::getAuthority).contains("quality:read", "quality:write");
    }
}
