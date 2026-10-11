package com.smartmanufacturing.security;

import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.jwt.Jwt;
import static org.junit.jupiter.api.Assertions.*;

/** 验证共享声明规则，防止缺少有效期的令牌绕过过期校验。 */
class SecurityClaimsTest {
    /** 即使受众和签发者正确，无过期时间的令牌也必须被拒绝。 */
    @Test void tokenWithoutExpirationIsRejected() {
        Jwt token = Jwt.withTokenValue("test").header("alg", "RS256").issuer("http://localhost:8080")
            .audience(List.of("manufacturing-api")).issuedAt(Instant.now()).subject("user").build();
        assertTrue(SecurityClaims.validators("http://localhost:8080", "manufacturing-api").validate(token).hasErrors());
    }

    /** 缺少受众声明返回校验错误，不能因空值触发内部异常。 */
    @Test void tokenWithoutAudienceIsRejectedWithoutThrowing() {
        Jwt token = Jwt.withTokenValue("test").header("alg", "RS256").issuer("http://localhost:8080")
            .expiresAt(Instant.now().plusSeconds(300)).subject("user").build();
        assertTrue(SecurityClaims.validators("http://localhost:8080", "manufacturing-api").validate(token).hasErrors());
    }

    /** 只从签名权限声明映射授权，不将未经定义的角色名称自动转换为权限。 */
    @Test void authoritiesComeFromPermissionsClaim() {
        Jwt token = Jwt.withTokenValue("test").header("alg", "RS256").subject("user")
            .claim("roles", List.of("ADMIN")).claim("permissions", List.of("device:read")).build();
        var authentication = SecurityClaims.authenticationConverter().convert(token);
        assertEquals(List.of("device:read"), authentication.getAuthorities().stream().map(a -> a.getAuthority()).toList());
    }
}
