package com.smartmanufacturing.auth;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.*;
import org.springframework.util.MultiValueMap;
import org.springframework.web.bind.annotation.*;
import com.nimbusds.jose.jwk.RSAKey;

/**
 * OAuth2 令牌端点（RFC 6749 4.4 client_credentials），只为服务间调用签发服务身份令牌。
 * <p>服务令牌与用户令牌使用同一把 RSA 私钥签名，资源服务继续用 /auth/jwks 验签；但服务令牌不含
 * username/roles/permissions，受众是具体资源服务（如 inventory-service）而非 manufacturing-api，
 * 并带 {@code token_type=service}，因此现有面向用户的接口会按受众校验直接拒绝它。</p>
 * <p>错误响应按 RFC 6749 5.2 使用 {error, error_description}，不使用业务统一的 ApiResponse 包装。</p>
 */
@RestController
public class ClientCredentialsController {
    /** 资源服务据此区分服务令牌与用户令牌。 */
    public static final String TOKEN_TYPE_CLAIM = "token_type";
    public static final String SERVICE_TOKEN_TYPE = "service";

    private final ServiceClientRegistry registry;
    private final JwtEncoder encoder;
    private final RSAKey key;
    private final String issuer;
    private final long ttl;

    public ClientCredentialsController(ServiceClientRegistry registry, JwtEncoder encoder, RSAKey key,
            @Value("${manufacturing.security.issuer:http://localhost:8080}") String issuer,
            @Value("${auth.client-token-ttl-seconds:300}") long ttl) {
        // 服务令牌不可吊销，有效期限制在 1–10 分钟；默认 5 分钟，调用方缓存并在临近过期时刷新。
        if (ttl < 60 || ttl > 600) throw new IllegalArgumentException("服务令牌有效期应在 60 至 600 秒之间");
        this.registry = registry; this.encoder = encoder; this.key = key; this.issuer = issuer; this.ttl = ttl;
    }

    /** 表单参数：grant_type（必须为 client_credentials）、可选 scope；客户端凭据优先取 HTTP Basic。 */
    @PostMapping(path = "/auth/oauth2/token", consumes = MediaType.APPLICATION_FORM_URLENCODED_VALUE)
    public ResponseEntity<Map<String, Object>> token(@RequestParam MultiValueMap<String, String> form,
            @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorization) {
        String[] credentials = credentials(authorization, form);
        if (credentials == null) return error(HttpStatus.UNAUTHORIZED, "invalid_client", "客户端认证失败");
        // 先认证客户端再校验授权类型，未认证的调用方得不到关于参数的细节。
        Optional<ServiceClientProperties.Client> found = registry.authenticate(credentials[0], credentials[1]);
        if (found.isEmpty()) return error(HttpStatus.UNAUTHORIZED, "invalid_client", "客户端认证失败");
        ServiceClientProperties.Client client = found.get();

        String grantType = single(form, "grant_type");
        if (grantType == null) return error(HttpStatus.BAD_REQUEST, "invalid_request", "缺少 grant_type 或参数重复");
        if (!"client_credentials".equals(grantType))
            return error(HttpStatus.BAD_REQUEST, "unsupported_grant_type", "仅支持 client_credentials");

        // 请求的 scope 必须是客户端允许范围的子集；未带 scope 时签发全部允许值（RFC 6749 3.3）。
        String requested = single(form, "scope");
        List<String> scopes;
        if (requested == null || requested.isBlank()) {
            scopes = List.copyOf(client.getScopes());
        } else {
            scopes = Arrays.stream(requested.trim().split(" +")).distinct().toList();
            if (!client.getScopes().containsAll(scopes))
                return error(HttpStatus.BAD_REQUEST, "invalid_scope", "请求的 scope 超出客户端允许范围");
        }
        String scope = String.join(" ", scopes);

        Instant now = Instant.now();
        JwtClaimsSet claims = JwtClaimsSet.builder().issuer(issuer).subject(client.getClientId())
            .audience(List.of(client.getAudience())).issuedAt(now).notBefore(now).expiresAt(now.plusSeconds(ttl))
            .id(UUID.randomUUID().toString()).claim("client_id", client.getClientId()).claim("scope", scope)
            .claim(TOKEN_TYPE_CLAIM, SERVICE_TOKEN_TYPE).build();
        String token = encoder.encode(JwtEncoderParameters.from(
            JwsHeader.with(SignatureAlgorithm.RS256).keyId(key.getKeyID()).build(), claims)).getTokenValue();
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("access_token", token);
        body.put("token_type", "Bearer");
        body.put("expires_in", ttl);
        body.put("scope", scope);
        // RFC 6749 5.1 要求令牌响应禁止缓存。
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).header("Pragma", "no-cache").body(body);
    }

    /**
     * 解析客户端凭据：优先 HTTP Basic（RFC 6749 2.3.1，用户名和密码先做 form-urlencoded 编码），
     * 其次表单 client_id/client_secret。两种方式同时出现视为非法请求，返回 null。
     */
    private static String[] credentials(String authorization, MultiValueMap<String, String> form) {
        boolean inForm = form.containsKey("client_id") || form.containsKey("client_secret");
        if (authorization != null && authorization.regionMatches(true, 0, "Basic ", 0, 6)) {
            if (inForm) return null;
            try {
                String decoded = new String(Base64.getDecoder().decode(authorization.substring(6).trim()), StandardCharsets.UTF_8);
                int colon = decoded.indexOf(':');
                if (colon < 0) return null;
                return new String[] {
                    java.net.URLDecoder.decode(decoded.substring(0, colon), StandardCharsets.UTF_8),
                    java.net.URLDecoder.decode(decoded.substring(colon + 1), StandardCharsets.UTF_8) };
            } catch (IllegalArgumentException e) {
                return null;
            }
        }
        if (authorization != null) return null;
        String id = single(form, "client_id"), secret = single(form, "client_secret");
        return id == null || secret == null ? null : new String[] { id, secret };
    }

    /** 读取单值参数；RFC 6749 3.2 规定参数不得重复，重复时按缺失处理。 */
    private static String single(MultiValueMap<String, String> form, String name) {
        List<String> values = form.get(name);
        return values == null || values.size() != 1 ? null : values.get(0);
    }

    /** 统一输出 RFC 6749 5.2 错误；invalid_client 附带 WWW-Authenticate 提示使用 Basic 认证。 */
    private static ResponseEntity<Map<String, Object>> error(HttpStatus status, String code, String description) {
        ResponseEntity.BodyBuilder builder = ResponseEntity.status(status).cacheControl(CacheControl.noStore()).header("Pragma", "no-cache");
        if (status == HttpStatus.UNAUTHORIZED) builder.header(HttpHeaders.WWW_AUTHENTICATE, "Basic realm=\"auth-service\"");
        return builder.body(Map.of("error", code, "error_description", description));
    }
}
