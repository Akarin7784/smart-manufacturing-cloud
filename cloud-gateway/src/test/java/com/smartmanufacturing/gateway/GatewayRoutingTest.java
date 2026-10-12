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
        for (String property : List.of("DEVICE_SERVICE_URL", "WORKORDER_SERVICE_URL", "AUTH_SERVICE_URL", "MASTERDATA_SERVICE_URL", "INVENTORY_SERVICE_URL", "QUALITY_SERVICE_URL"))
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

    /** 主数据四组资源均路由到主数据服务并移除 /api 前缀；只读权限即可查询。 */
    @Test void routesMasterdataReads() {
        String access = token("http://localhost:8080", "manufacturing-api", Instant.now().plusSeconds(300), List.of("masterdata:read"));
        for (String path : List.of("/materials", "/workcenters/WC-CNC", "/boms/FG-GR60-20/explosion", "/routings/FG-GR60-20/R01"))
            client().get().uri("/api" + path).headers(h -> h.setBearerAuth(access)).exchange()
                .expectStatus().isOk().expectBody(String.class).value(body -> Assertions.assertTrue(body.startsWith(path + "|")));
    }

    /** 主数据写与发布权限分离：只有 masterdata:write 不能发布，只读身份不能写。 */
    @Test void masterdataReleaseRequiresDedicatedPermission() {
        String writer = token("http://localhost:8080", "manufacturing-api", Instant.now().plusSeconds(300), List.of("masterdata:read", "masterdata:write"));
        client().post().uri("/api/boms/FG-GR60-20/A02/release").headers(h -> h.setBearerAuth(writer)).exchange().expectStatus().isForbidden();
        client().post().uri("/api/boms").headers(h -> h.setBearerAuth(writer)).exchange().expectStatus().isOk();
        String reader = token("http://localhost:8080", "manufacturing-api", Instant.now().plusSeconds(300), List.of("masterdata:read"));
        client().put().uri("/api/materials/PP-KEY-6").headers(h -> h.setBearerAuth(reader)).exchange().expectStatus().isForbidden();
        String releaser = token("http://localhost:8080", "manufacturing-api", Instant.now().plusSeconds(300), List.of("masterdata:release"));
        client().post().uri("/api/routings/FG-GR60-20/R02/release").headers(h -> h.setBearerAuth(releaser)).exchange().expectStatus().isOk();
    }

    /** 报工、冲销、手工完工、取消需要 workorder:write；只读令牌被拒，进度查询只需 workorder:read。 */
    @Test void operationReportingRequiresWritePermission() {
        String reader = token("http://localhost:8080", "manufacturing-api", Instant.now().plusSeconds(300), List.of("workorder:read"));
        String writer = token("http://localhost:8080", "manufacturing-api", Instant.now().plusSeconds(300), List.of("workorder:read", "workorder:write"));
        for (String uri : List.of("/api/workorders/WO1/operations/OP10/reports", "/api/workorders/WO1/reports/R1/reversal", "/api/workorders/WO1/complete", "/api/workorders/WO1/cancel")) {
            client().post().uri(uri).headers(h -> h.setBearerAuth(reader)).exchange().expectStatus().isForbidden();
            client().post().uri(uri).headers(h -> h.setBearerAuth(writer)).exchange().expectStatus().isOk();
        }
        client().get().uri("/api/workorders/WO1/progress").headers(h -> h.setBearerAuth(reader)).exchange().expectStatus().isOk();
    }

    /** 服务令牌端点无需 JWT 即可转发到认证服务（凭据由认证服务校验），但仅放行 POST，其他方法仍被拒绝。 */
    @Test void tokenEndpointIsPublicForPostOnly() {
        client().post().uri("/api/auth/oauth2/token").header("Content-Type", "application/x-www-form-urlencoded")
            .bodyValue("grant_type=client_credentials").exchange()
            .expectStatus().isOk().expectBody(String.class).value(body -> Assertions.assertTrue(body.startsWith("/auth/oauth2/token|")));
        client().get().uri("/api/auth/oauth2/token").exchange().expectStatus().isUnauthorized();
    }

    /** 服务令牌（受众 inventory-service、无 permissions）不能经网关访问面向用户的接口。 */
    @Test void serviceTokenRejectedOnUserRoutes() {
        String service = token("http://localhost:8080", "inventory-service", Instant.now().plusSeconds(300), List.of());
        client().get().uri("/api/workorders").header("Authorization", "Bearer " + service).exchange().expectStatus().isUnauthorized();
    }

    /** 库存查询经网关转发并移除 /api 前缀，需 inventory:read；无该权限返回 403。 */
    @Test void routesInventoryQueriesWithReadPermission() {
        String inv = token("http://localhost:8080", "manufacturing-api", Instant.now().plusSeconds(300), List.of("inventory:read"));
        client().get().uri("/api/inventory/stock/availability?materialCode=PP-BRG-6204").headers(h -> h.setBearerAuth(inv)).exchange()
            .expectStatus().isOk().expectBody(String.class).value(body -> Assertions.assertTrue(body.startsWith("/inventory/stock/availability|")));
        client().get().uri("/api/inventory/stock").headers(h -> h.setBearerAuth(reader())).exchange().expectStatus().isForbidden();
        // 只读权限不能出入库。
        client().post().uri("/api/inventory/inbound").headers(h -> h.setBearerAuth(inv)).exchange().expectStatus().isForbidden();
    }

    /** 预留接口只供服务间直连：即使用户持有全部库存权限，网关也拒绝转发。 */
    @Test void deniesInventoryReservationsAtGateway() {
        String inv = token("http://localhost:8080", "manufacturing-api", Instant.now().plusSeconds(300), List.of("inventory:read", "inventory:write"));
        client().post().uri("/api/inventory/reservations").headers(h -> h.setBearerAuth(inv)).exchange().expectStatus().isForbidden();
        client().get().uri("/api/inventory/reservations/R1").headers(h -> h.setBearerAuth(inv)).exchange().expectStatus().isForbidden();
    }

    /** 质检查询需 quality:read 并移除 /api 前缀；只读权限不能处置，写入需 quality:write。 */
    @Test void routesQualityWithReadAndWritePermissions() {
        String reader = token("http://localhost:8080", "manufacturing-api", Instant.now().plusSeconds(300), List.of("quality:read"));
        String writer = token("http://localhost:8080", "manufacturing-api", Instant.now().plusSeconds(300), List.of("quality:read", "quality:write"));
        client().get().uri("/api/quality/ncrs").headers(h -> h.setBearerAuth(reader)).exchange()
            .expectStatus().isOk().expectBody(String.class).value(body -> Assertions.assertTrue(body.startsWith("/quality/ncrs|")));
        client().get().uri("/api/quality/ncrs").headers(h -> h.setBearerAuth(reader())).exchange().expectStatus().isForbidden();
        client().post().uri("/api/quality/ncrs/NCR-1/dispositions").headers(h -> h.setBearerAuth(reader)).exchange().expectStatus().isForbidden();
        client().post().uri("/api/quality/ncrs/NCR-1/dispositions").headers(h -> h.setBearerAuth(writer)).exchange().expectStatus().isOk();
        // 契约之外的写路径一律拒绝。
        client().post().uri("/api/quality/ncrs").headers(h -> h.setBearerAuth(writer)).exchange().expectStatus().isForbidden();
    }

    // 以下权限清单与 auth-service 的 Role 枚举一致（网关模块不依赖认证服务，这里按角色复制；契约变更时同步）。
    static final List<String> OPERATOR = List.of("device:read", "workorder:read", "workorder:write", "masterdata:read", "inventory:read", "inventory:write", "quality:read");
    static final List<String> QUALITY_INSPECTOR = List.of("device:read", "workorder:read", "masterdata:read", "inventory:read", "quality:read", "quality:write");
    static final List<String> ADMIN = List.of("device:read", "workorder:read", "workorder:write", "config:read", "metrics:read", "user:manage",
        "masterdata:read", "masterdata:write", "masterdata:release", "inventory:read", "inventory:write", "quality:read", "quality:write");

    private String as(List<String> role) { return token("http://localhost:8080", "manufacturing-api", Instant.now().plusSeconds(300), role); }

    /** 操作员只读质检：查询放行，处置 403。 */
    @Test void operatorMayReadButNotDisposeQuality() {
        client().get().uri("/api/quality/ncrs").headers(h -> h.setBearerAuth(as(OPERATOR))).exchange().expectStatus().isOk();
        client().post().uri("/api/quality/ncrs/NCR-1/dispositions").headers(h -> h.setBearerAuth(as(OPERATOR))).exchange().expectStatus().isForbidden();
    }

    /** 质检员与管理员：查询、评审、处置、关闭均放行。 */
    @Test void inspectorAndAdminMayReadAndDisposeQuality() {
        for (List<String> role : List.of(QUALITY_INSPECTOR, ADMIN)) {
            client().get().uri("/api/quality/ncrs/NCR-1").headers(h -> h.setBearerAuth(as(role))).exchange().expectStatus().isOk();
            for (String action : List.of("review", "dispositions", "close"))
                client().post().uri("/api/quality/ncrs/NCR-1/" + action).headers(h -> h.setBearerAuth(as(role))).exchange().expectStatus().isOk();
        }
    }
}
