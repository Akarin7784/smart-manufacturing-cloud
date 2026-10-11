package com.smartmanufacturing.gateway;

import com.nimbusds.jose.jwk.*;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.nimbusds.jose.jwk.source.ImmutableJWKSet;
import com.sun.net.httpserver.HttpServer;
import java.net.*;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.*;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.security.oauth2.jwt.*;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.test.context.*;
import org.springframework.test.web.reactive.server.WebTestClient;

/** 使用真实 RSA 签名和 HTTP JWKS 验证网关的认证、越权防护及业务转发。 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("local")
class GatewayRoutingTest {
    static final HttpServer upstream;
    static final RSAKey key;
    static final JwtEncoder encoder;
    static {
        try {
            key = new RSAKeyGenerator(2048).keyID("gateway-test").generate();
            encoder = new NimbusJwtEncoder(new ImmutableJWKSet<>(new JWKSet(key)));
            upstream = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            upstream.createContext("/auth/jwks", exchange -> {
                byte[] body = new JWKSet(key.toPublicJWK()).toString().getBytes(java.nio.charset.StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Content-Type", "application/json");
                exchange.sendResponseHeaders(200, body.length);
                try (var out = exchange.getResponseBody()) { out.write(body); }
            });
            upstream.createContext("/", exchange -> {
                String body = exchange.getRequestURI().getPath() + "|" + exchange.getRequestHeaders().getFirst("X-Request-Id")
                    + "|" + exchange.getRequestHeaders().getFirst("Authorization");
                byte[] bytes = body.getBytes(java.nio.charset.StandardCharsets.UTF_8);
                exchange.sendResponseHeaders(200, bytes.length);
                try (var out = exchange.getResponseBody()) { out.write(bytes); }
            });
            upstream.start();
        } catch (Exception e) { throw new ExceptionInInitializerError(e); }
    }

    /** 单独覆盖目标地址，保持路由列表和过滤器配置来自实际配置文件。 */
    @DynamicPropertySource static void routes(DynamicPropertyRegistry registry) {
        for (String property : List.of("DEVICE_SERVICE_URL", "WORKORDER_SERVICE_URL", "AUTH_SERVICE_URL"))
            registry.add(property, () -> "http://127.0.0.1:" + upstream.getAddress().getPort());
        registry.add("manufacturing.security.jwk-set-uri", () -> "http://127.0.0.1:" + upstream.getAddress().getPort() + "/auth/jwks");
    }
    @LocalServerPort int port;
    @AfterAll static void stop() { upstream.stop(0); }
    private WebTestClient client() { return WebTestClient.bindToServer().baseUrl("http://localhost:" + port).build(); }

    /** 生成只包含测试所需权限的真实签名令牌，按参数覆盖安全声明。 */
    private String token(String issuer, String audience, Instant expiry, List<String> permissions) {
        JwtClaimsSet claims = JwtClaimsSet.builder().issuer(issuer).subject("test-user")
            .audience(List.of(audience)).issuedAt(Instant.now().minusSeconds(300)).expiresAt(expiry)
            .claim("permissions", permissions).build();
        return encoder.encode(JwtEncoderParameters.from(
            JwsHeader.with(SignatureAlgorithm.RS256).keyId(key.getKeyID()).build(), claims)).getTokenValue();
    }
    private String reader() {
        return token("http://localhost:8080", "manufacturing-api", Instant.now().plusSeconds(300), List.of("device:read", "workorder:read"));
    }

    /** 认证后仍保持前缀移除与请求标识替换，并传递 Bearer 令牌。 */
    @Test void stripsPrefixAndReplacesUntrustedRequestId() {
        String access = reader();
        client().get().uri("/api/devices/D001").header("X-Request-Id", "client-value")
            .headers(headers -> headers.setBearerAuth(access)).exchange()
            .expectStatus().isOk().expectHeader().exists("X-Request-Id")
            .expectBody(String.class).value(body -> {
                Assertions.assertTrue(body.startsWith("/devices/D001|"));
                Assertions.assertFalse(body.contains("client-value"));
                Assertions.assertTrue(body.endsWith("Bearer " + access));
            });
    }

    /** 查看者允许读取工单列表。 */
    @Test void routesWorkorders() {
        client().get().uri("/api/workorders").headers(h -> h.setBearerAuth(reader())).exchange()
            .expectStatus().isOk().expectBody(String.class).value(body -> Assertions.assertTrue(body.startsWith("/workorders|")));
    }

    /** 无令牌及伪造角色请求头不会授予访问权限。 */
    @Test void rejectsAnonymousAndForgedIdentity() {
        client().get().uri("/api/devices").header("X-Role", "ADMIN").exchange()
            .expectStatus().isUnauthorized().expectBody().jsonPath("$.code").isEqualTo("AUTHENTICATION_REQUIRED");
    }

    /** 已认证的只读身份也不能调用工单写接口。 */
    @Test void readerCannotWriteOrders() {
        client().post().uri("/api/workorders").headers(h -> h.setBearerAuth(reader())).exchange()
            .expectStatus().isForbidden().expectBody().jsonPath("$.code").isEqualTo("ACCESS_DENIED");
    }

    /** 验证过期、错误签发者和错误受众的令牌均被拒绝。 */
    @Test void rejectsInvalidClaims() {
        for (String access : List.of(
            token("http://localhost:8080", "manufacturing-api", Instant.now().minusSeconds(120), List.of("device:read")),
            token("https://wrong-issuer", "manufacturing-api", Instant.now().plusSeconds(300), List.of("device:read")),
            token("http://localhost:8080", "other-api", Instant.now().plusSeconds(300), List.of("device:read")))) {
            client().get().uri("/api/devices").headers(h -> h.setBearerAuth(access)).exchange().expectStatus().isUnauthorized();
        }
    }

    /** 修改签名后仍保留完整 JWT 结构，确保拒绝原因是验签失败。 */
    @Test void rejectsTamperedSignature() {
        String[] parts = reader().split("\\.");
        byte[] signature = java.util.Base64.getUrlDecoder().decode(parts[2]);
        signature[0] ^= 1;
        String tampered = parts[0] + "." + parts[1] + "." + java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(signature);
        client().get().uri("/api/devices").headers(h -> h.setBearerAuth(tampered)).exchange().expectStatus().isUnauthorized();
    }

    /** 公钥发现接口匿名可读，公开响应不得包含私钥材料。 */
    @Test void publicJwksContainsNoPrivateKey() {
        client().get().uri("/api/auth/jwks").exchange().expectStatus().isOk()
            .expectBody().jsonPath("$.keys[0].n").exists().jsonPath("$.keys[0].d").doesNotExist();
    }
}
