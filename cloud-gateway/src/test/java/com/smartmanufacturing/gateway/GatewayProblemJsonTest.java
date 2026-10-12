package com.smartmanufacturing.gateway;

import com.alibaba.csp.sentinel.adapter.gateway.sc.callback.GatewayCallbackManager;
import com.nimbusds.jose.jwk.*;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.nimbusds.jose.jwk.source.ImmutableJWKSet;
import com.sun.net.httpserver.HttpServer;
import java.net.*;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.*;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.*;
import org.springframework.test.context.*;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.springframework.web.reactive.function.server.EntityResponse;

/**
 * 网关自身错误统一为 RFC 9457 problem+json：401 / 403 / 429 / 503 均断言 Content-Type 与
 * type/title/status/detail/instance 五个字段，并确认旧的 code 字段已移除。
 * 业务路由指向一个已关闭的端口，用来真实触发"下游不可用"（ConnectException → 503）。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("local")
class GatewayProblemJsonTest {
    static final HttpServer jwks;
    static final RSAKey key;
    static final JwtEncoder encoder;
    static final int closedPort;
    static {
        try {
            key = new RSAKeyGenerator(2048).keyID("problem-test").generate();
            encoder = new NimbusJwtEncoder(new ImmutableJWKSet<>(new JWKSet(key)));
            jwks = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            jwks.createContext("/auth/jwks", exchange -> {
                byte[] body = new JWKSet(key.toPublicJWK()).toString().getBytes(java.nio.charset.StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Content-Type", "application/json");
                exchange.sendResponseHeaders(200, body.length);
                try (var out = exchange.getResponseBody()) { out.write(body); }
            });
            jwks.start();
            // 先占用再释放一个端口，得到一个当前无人监听的地址。
            try (ServerSocket s = new ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))) { closedPort = s.getLocalPort(); }
        } catch (Exception e) { throw new ExceptionInInitializerError(e); }
    }

    @DynamicPropertySource static void routes(DynamicPropertyRegistry registry) {
        for (String property : List.of("DEVICE_SERVICE_URL", "WORKORDER_SERVICE_URL", "AUTH_SERVICE_URL", "MASTERDATA_SERVICE_URL", "INVENTORY_SERVICE_URL", "QUALITY_SERVICE_URL"))
            registry.add(property, () -> "http://127.0.0.1:" + closedPort);
        registry.add("manufacturing.security.jwk-set-uri", () -> "http://127.0.0.1:" + jwks.getAddress().getPort() + "/auth/jwks");
    }
    @LocalServerPort int port;
    @AfterAll static void stop() { jwks.stop(0); }
    private WebTestClient client() { return WebTestClient.bindToServer().baseUrl("http://localhost:" + port).build(); }

    private String token(List<String> permissions) {
        JwtClaimsSet claims = JwtClaimsSet.builder().issuer("http://localhost:8080").subject("test-user")
            .audience(List.of("manufacturing-api")).issuedAt(Instant.now().minusSeconds(60)).expiresAt(Instant.now().plusSeconds(300))
            .claim("permissions", permissions).build();
        return encoder.encode(JwtEncoderParameters.from(
            JwsHeader.with(SignatureAlgorithm.RS256).keyId(key.getKeyID()).build(), claims)).getTokenValue();
    }

    /** 统一断言 problem+json 的五个字段，且不再输出 code。 */
    /** 文档 docs/authentication.md 网关 Problem 表中的 type → 固定中文 title，测试按此断言，不从枚举反推。 */
    static final Map<String, String> TITLES = Map.of(
        "gateway-authentication-required", "需要认证",
        "gateway-access-denied", "没有访问权限",
        "gateway-not-found", "资源不存在",
        "gateway-rate-limited", "请求过于频繁",
        "gateway-service-unavailable", "下游服务不可用");

    /** 枚举与文档表一一对应：type 集合一致、title 与表中固定值一致且为中文。 */
    @Test void everyTypeHasFixedChineseTitle() {
        Map<String, String> actual = new java.util.HashMap<>();
        for (GatewayProblem p : GatewayProblem.values()) {
            String title = (String) p.body("/x").get("title");
            Assertions.assertTrue(title.codePoints().anyMatch(c -> Character.UnicodeScript.of(c) == Character.UnicodeScript.HAN),
                p + " 的 title 必须是中文：" + title);
            actual.put(p.type(), title);
        }
        Assertions.assertEquals(TITLES, actual);
    }

    private void assertProblem(WebTestClient.ResponseSpec spec, int status, String type, String instance) {
        spec.expectStatus().isEqualTo(status)
            .expectHeader().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON)
            .expectBody()
            .jsonPath("$.type").isEqualTo(type)
            .jsonPath("$.title").isEqualTo(TITLES.get(type))
            .jsonPath("$.status").isEqualTo(status)
            .jsonPath("$.detail").isNotEmpty()
            .jsonPath("$.instance").isEqualTo(instance)
            .jsonPath("$.code").doesNotExist();
    }

    /** 无令牌：401，带 Bearer 质询。 */
    @Test void unauthenticatedIsProblemJson() {
        var spec = client().get().uri("/api/quality/ncrs").exchange();
        spec.expectHeader().valueEquals("WWW-Authenticate", "Bearer");
        assertProblem(spec, 401, "gateway-authentication-required", "/api/quality/ncrs");
    }

    /** 已认证但缺权限：403；instance 不回显查询串。 */
    @Test void forbiddenIsProblemJson() {
        assertProblem(client().post().uri("/api/quality/ncrs/NCR-1/dispositions?x=1").headers(h -> h.setBearerAuth(token(List.of("quality:read")))).exchange(),
            403, "gateway-access-denied", "/api/quality/ncrs/NCR-1/dispositions");
    }

    /** 放行后下游无法连接：503。 */
    @Test void unreachableServiceIsProblemJson() {
        assertProblem(client().get().uri("/api/quality/ncrs").headers(h -> h.setBearerAuth(token(List.of("quality:read")))).exchange(),
            503, "gateway-service-unavailable", "/api/quality/ncrs");
    }

    /** Sentinel 网关限流回调：429 problem+json（直接调用已注册的回调，无需真的打满 QPS）。 */
    @Test void rateLimitedIsProblemJson() {
        var exchange = MockServerWebExchange.from(MockServerHttpRequest.get("/api/workorders?page=1"));
        var response = GatewayCallbackManager.getBlockHandler().handleRequest(exchange, new RuntimeException("blocked")).block();
        Assertions.assertNotNull(response);
        Assertions.assertEquals(HttpStatus.TOO_MANY_REQUESTS, response.statusCode());
        Assertions.assertEquals(MediaType.APPLICATION_PROBLEM_JSON, response.headers().getContentType());
        Assertions.assertEquals(Map.of("type", "gateway-rate-limited", "title", "请求过于频繁", "status", 429,
            "detail", "请求过于频繁，请稍后重试", "instance", "/api/workorders"), ((EntityResponse<?>) response).entity());
    }

    /** 负载均衡找不到实例时网关抛出的 503 ResponseStatusException 同样识别为下游不可用；其他异常不拦截。 */
    @Test void classifiesServiceUnavailable() {
        Assertions.assertTrue(GatewayProblemExceptionHandler.isServiceUnavailable(
            new org.springframework.web.server.ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Unable to find instance")));
        Assertions.assertTrue(GatewayProblemExceptionHandler.isServiceUnavailable(new RuntimeException(new ConnectException("refused"))));
        Assertions.assertFalse(GatewayProblemExceptionHandler.isServiceUnavailable(new IllegalStateException("bug")));
    }
}
