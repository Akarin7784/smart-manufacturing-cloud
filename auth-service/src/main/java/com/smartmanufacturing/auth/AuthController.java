package com.smartmanufacturing.auth;

import com.nimbusds.jose.jwk.*;
import com.smartmanufacturing.api.ApiResponse;
import jakarta.validation.Valid;
import java.time.Instant;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.*;
import org.springframework.web.bind.annotation.*;

/** 提供登录、公钥发现、当前身份和管理员用户管理，不暴露私钥或密码散列。 */
@RestController
@RequestMapping("/auth")
public class AuthController {
    private final AuthUserService users;
    private final JwtEncoder encoder;
    private final RSAKey key;
    private final String issuer;
    private final String audience;
    private final long ttl;

    public AuthController(AuthUserService users, JwtEncoder encoder, RSAKey key,
            @Value("${manufacturing.security.issuer:http://localhost:8080}") String issuer,
            @Value("${manufacturing.security.audience:manufacturing-api}") String audience,
            @Value("${auth.token-ttl-seconds:900}") long ttl) {
        if (ttl < 60 || ttl > 3600) throw new IllegalArgumentException("访问令牌有效期应在 60 至 3600 秒之间");
        this.users = users; this.encoder = encoder; this.key = key; this.issuer = issuer; this.audience = audience; this.ttl = ttl;
    }

    /** 从数据库确定角色后签发令牌；登录响应禁止缓存，避免令牌进入共享缓存。 */
    @PostMapping("/login")
    public ResponseEntity<ApiResponse<TokenResponse>> login(@Valid @RequestBody LoginRequest request) {
        UserView user = users.authenticate(request);
        Instant now = Instant.now();
        JwtClaimsSet claims = JwtClaimsSet.builder().issuer(issuer).subject(user.id())
            .audience(List.of(audience)).issuedAt(now).notBefore(now).expiresAt(now.plusSeconds(ttl))
            .id(UUID.randomUUID().toString()).claim("username", user.username())
            .claim("roles", List.of(user.role().name())).claim("permissions", user.role().permissions()).build();
        String token = encoder.encode(JwtEncoderParameters.from(
            JwsHeader.with(SignatureAlgorithm.RS256).keyId(key.getKeyID()).build(), claims)).getTokenValue();
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).header("Pragma", "no-cache")
            .body(ApiResponse.ok(new TokenResponse(token, "Bearer", ttl, user.username(), user.role(), user.role().permissions())));
    }

    /** JWKS 仅包含 RSA 公钥，资源服务不需要访问任何私钥材料。 */
    @GetMapping("/jwks") public Map<String, Object> jwks() { return new JWKSet(key.toPublicJWK()).toJSONObject(); }

    /** 返回经过验签的令牌身份；权限是签发时的快照，不表示用户状态实时查询。 */
    @GetMapping("/me") public ApiResponse<Map<String, Object>> me(@AuthenticationPrincipal Jwt jwt) {
        return ApiResponse.ok(Map.of("id", jwt.getSubject(), "username", jwt.getClaimAsString("username"),
            "roles", jwt.getClaimAsStringList("roles"), "permissions", jwt.getClaimAsStringList("permissions")));
    }

    /** 仅管理员可以查看用户列表，响应永远不包含密码散列。 */
    @GetMapping("/users") @PreAuthorize("hasAuthority('user:manage')")
    public ApiResponse<List<UserView>> users() { return ApiResponse.ok(users.list()); }

    /** 仅管理员可以创建账号和指定系统角色。 */
    @PostMapping("/users") @ResponseStatus(HttpStatus.CREATED) @PreAuthorize("hasAuthority('user:manage')")
    public ApiResponse<UserView> create(@Valid @RequestBody CreateUserRequest request) { return ApiResponse.ok(users.create(request)); }

    /** 管理员可调整角色或启停账号，但不能通过此接口锁死自己的账号。 */
    @PatchMapping("/users/{id}") @PreAuthorize("hasAuthority('user:manage')")
    public ApiResponse<UserView> update(@PathVariable String id, @Valid @RequestBody UpdateUserRequest request,
            @AuthenticationPrincipal Jwt jwt) { return ApiResponse.ok(users.update(id, request, jwt.getSubject())); }
}
