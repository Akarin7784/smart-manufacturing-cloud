package com.smartmanufacturing.auth;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.test.context.*;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * 验证 client_credentials 令牌端点：成功签发、错误密钥、未知客户端、错误授权类型、越权 scope，
 * 以及令牌声明（aud、scope、5 分钟有效期）可用公开 JWKS 验签，且不能冒充用户令牌访问 /auth/me。
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class ClientCredentialsTokenTest {
    static final String SECRET = "test-workorder-client-secret-2026";
    static final Path directory;
    static {
        try { directory = Files.createTempDirectory("manufacturing-auth-cc-test-"); }
        catch (Exception e) { throw new ExceptionInInitializerError(e); }
    }

    /** 测试只注入密钥的 BCrypt 散列，与生产一致：认证服务从不持有明文密钥。 */
    @DynamicPropertySource static void properties(DynamicPropertyRegistry registry) {
        registry.add("auth.private-key-path", () -> directory.resolve("signing-key.json").toString());
        String hash = new BCryptPasswordEncoder(4).encode(SECRET);
        // 通过占位符变量注入，保持客户端列表其余配置来自实际 application.yml（覆盖列表下标会替换整个列表）。
        registry.add("WORKORDER_CLIENT_SECRET_HASH", () -> hash);
    }

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper mapper;

    private static String basic(String id, String secret) {
        return "Basic " + Base64.getEncoder().encodeToString((id + ":" + secret).getBytes(StandardCharsets.UTF_8));
    }

    private ResultActions request(String authorization, String form) throws Exception {
        var builder = post("/auth/oauth2/token").contentType(MediaType.APPLICATION_FORM_URLENCODED).content(form);
        if (authorization != null) builder.header(HttpHeaders.AUTHORIZATION, authorization);
        return mvc.perform(builder);
    }

    /** 用公开 JWKS 中的公钥验签，模拟库存服务的校验方式，而不是直接使用私钥。 */
    private Jwt verifyWithJwks(String token) throws Exception {
        String jwks = mvc.perform(get("/auth/jwks")).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        RSAKey publicKey = JWKSet.parse(jwks).getKeys().get(0).toRSAKey();
        assertFalse(publicKey.isPrivate());
        return NimbusJwtDecoder.withPublicKey(publicKey.toRSAPublicKey()).build().decode(token);
    }

    /** Basic 认证成功：响应符合 RFC 6749 5.1，令牌声明满足团队约定（aud/scope/5 分钟/服务令牌标记，无用户声明）。 */
    @Test void basicAuthIssuesServiceToken() throws Exception {
        String body = request(basic("workorder-service", SECRET), "grant_type=client_credentials&scope=inventory%3Areserve")
            .andExpect(status().isOk()).andExpect(header().string("Cache-Control", "no-store"))
            .andExpect(jsonPath("$.token_type").value("Bearer")).andExpect(jsonPath("$.expires_in").value(300))
            .andExpect(jsonPath("$.scope").value("inventory:reserve"))
            .andReturn().getResponse().getContentAsString();
        JsonNode json = mapper.readTree(body);
        Jwt jwt = verifyWithJwks(json.path("access_token").asText());
        assertEquals("workorder-service", jwt.getSubject());
        assertEquals("workorder-service", jwt.getClaimAsString("client_id"));
        assertEquals("inventory:reserve", jwt.getClaimAsString("scope"));
        assertEquals(List.of("inventory-service"), jwt.getAudience());
        assertEquals("service", jwt.getClaimAsString("token_type"));
        assertNotNull(jwt.getId());
        assertEquals(Duration.ofMinutes(5), Duration.between(jwt.getIssuedAt(), jwt.getExpiresAt()));
        assertTrue(jwt.getIssuedAt().isAfter(Instant.now().minusSeconds(30)));
        // 服务令牌不得携带用户身份或权限，防止被当成用户令牌使用。
        assertNull(jwt.getClaim("username"));
        assertNull(jwt.getClaim("roles"));
        assertNull(jwt.getClaim("permissions"));
    }

    /** 表单方式传 client_id/client_secret 也可认证；不带 scope 时签发全部允许值。 */
    @Test void formCredentialsWithoutScopeGetAllowedScopes() throws Exception {
        request(null, "grant_type=client_credentials&client_id=workorder-service&client_secret=" + SECRET)
            .andExpect(status().isOk()).andExpect(jsonPath("$.scope").value("inventory:reserve"));
    }

    /** 错误密钥与未知客户端返回完全相同的 invalid_client 401，并带 WWW-Authenticate，避免枚举客户端。 */
    @Test void wrongSecretAndUnknownClientAreUniform401() throws Exception {
        for (String auth : List.of(basic("workorder-service", "wrong-secret"), basic("unknown-service", SECRET))) {
            request(auth, "grant_type=client_credentials").andExpect(status().isUnauthorized())
                .andExpect(header().string("WWW-Authenticate", org.hamcrest.Matchers.startsWith("Basic")))
                .andExpect(jsonPath("$.error").value("invalid_client"))
                .andExpect(jsonPath("$.access_token").doesNotExist());
        }
        // 未提供任何凭据、或 Basic 与表单凭据同时出现，同样按客户端认证失败处理。
        request(null, "grant_type=client_credentials").andExpect(status().isUnauthorized());
        request(basic("workorder-service", SECRET), "grant_type=client_credentials&client_id=workorder-service&client_secret=" + SECRET)
            .andExpect(status().isUnauthorized());
    }

    /** 非 client_credentials 授权类型返回 unsupported_grant_type，缺少 grant_type 返回 invalid_request。 */
    @Test void badGrantTypeRejected() throws Exception {
        request(basic("workorder-service", SECRET), "grant_type=password&username=admin&password=x")
            .andExpect(status().isBadRequest()).andExpect(jsonPath("$.error").value("unsupported_grant_type"));
        request(basic("workorder-service", SECRET), "scope=inventory%3Areserve")
            .andExpect(status().isBadRequest()).andExpect(jsonPath("$.error").value("invalid_request"));
    }

    /** 请求客户端未被授予的 scope 返回 invalid_scope，不签发部分 scope 的令牌。 */
    @Test void scopeOutsideAllowedSetRejected() throws Exception {
        request(basic("workorder-service", SECRET), "grant_type=client_credentials&scope=inventory%3Areserve%20user%3Amanage")
            .andExpect(status().isBadRequest()).andExpect(jsonPath("$.error").value("invalid_scope"));
    }

    /** 服务令牌受众不是 manufacturing-api，访问面向用户的 /auth/me 必须被拒绝。 */
    @Test void serviceTokenCannotActAsUser() throws Exception {
        String body = request(basic("workorder-service", SECRET), "grant_type=client_credentials")
            .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        String token = mapper.readTree(body).path("access_token").asText();
        mvc.perform(get("/auth/me").header(HttpHeaders.AUTHORIZATION, "Bearer " + token)).andExpect(status().isUnauthorized());
    }
}
