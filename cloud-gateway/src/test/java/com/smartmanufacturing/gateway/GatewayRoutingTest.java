package com.smartmanufacturing.gateway;

import com.nimbusds.jose.jwk.*;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.nimbusds.jose.jwk.source.ImmutableJWKSet;
import com.sun.net.httpserver.HttpServer;
import java.net.*;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import java.time.Instant;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.UncheckedIOException;
import java.nio.file.*;
import java.util.List;
import java.util.Map;
import java.util.Objects;
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
            .expectStatus().isUnauthorized().expectHeader().contentType("application/problem+json")
            .expectBody().jsonPath("$.type").isEqualTo("gateway-authentication-required")
            .jsonPath("$.title").isEqualTo(GatewayProblemJsonTest.TITLES.get("gateway-authentication-required")).jsonPath("$.code").doesNotExist();
    }

    /** 已认证的只读身份也不能调用工单写接口。 */
    @Test void readerCannotWriteOrders() {
        client().post().uri("/api/workorders").headers(h -> h.setBearerAuth(reader())).exchange()
            .expectStatus().isForbidden().expectHeader().contentType("application/problem+json")
            .expectBody().jsonPath("$.type").isEqualTo("gateway-access-denied")
            .jsonPath("$.title").isEqualTo(GatewayProblemJsonTest.TITLES.get("gateway-access-denied")).jsonPath("$.code").doesNotExist();
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

    /** PR2：关闭 / 撤回下达需要 workorder:write；分页列表只需 workorder:read。 */
    @Test void closeAndWithdrawRequireWritePermission() {
        String reader = token("http://localhost:8080", "manufacturing-api", Instant.now().plusSeconds(300), List.of("workorder:read"));
        String writer = token("http://localhost:8080", "manufacturing-api", Instant.now().plusSeconds(300), List.of("workorder:read", "workorder:write"));
        for (String uri : List.of("/api/workorders/WO1/close", "/api/workorders/WO1/withdraw-release")) {
            client().post().uri(uri).headers(h -> h.setBearerAuth(reader)).exchange().expectStatus().isForbidden();
            client().post().uri(uri).headers(h -> h.setBearerAuth(writer)).exchange().expectStatus().isOk();
        }
        client().get().uri("/api/workorders?page=0&size=20&status=RELEASED&keyword=GR").headers(h -> h.setBearerAuth(reader)).exchange().expectStatus().isOk();
    }

    /** PR2：工作中心 / 工位修改与删除、删除草稿需要 masterdata:write；作废需要 masterdata:release。 */
    @Test void masterdataLifecyclePermissions() {
        String reader = token("http://localhost:8080", "manufacturing-api", Instant.now().plusSeconds(300), List.of("masterdata:read"));
        String writer = token("http://localhost:8080", "manufacturing-api", Instant.now().plusSeconds(300), List.of("masterdata:read", "masterdata:write"));
        String releaser = token("http://localhost:8080", "manufacturing-api", Instant.now().plusSeconds(300), List.of("masterdata:release"));
        for (String uri : List.of("/api/workcenters/WC-CNC", "/api/workcenters/WC-CNC/stations/ST-CNC-01")) {
            client().put().uri(uri).headers(h -> h.setBearerAuth(reader)).exchange().expectStatus().isForbidden();
            client().put().uri(uri).headers(h -> h.setBearerAuth(writer)).exchange().expectStatus().isOk();
        }
        for (String uri : List.of("/api/workcenters/WC-CNC", "/api/workcenters/WC-CNC/stations/ST-CNC-01", "/api/boms/FG-GR60-20/A09", "/api/routings/FG-GR60-20/R09")) {
            client().delete().uri(uri).headers(h -> h.setBearerAuth(reader)).exchange().expectStatus().isForbidden();
            client().delete().uri(uri).headers(h -> h.setBearerAuth(writer)).exchange().expectStatus().isOk();
        }
        for (String uri : List.of("/api/boms/FG-GR60-20/A01/retire", "/api/routings/FG-GR60-20/R01/retire")) {
            client().post().uri(uri).headers(h -> h.setBearerAuth(writer)).exchange().expectStatus().isForbidden();
            client().post().uri(uri).headers(h -> h.setBearerAuth(releaser)).exchange().expectStatus().isOk();
        }
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

    /**
     * 内部路径清单逐条断言：每个服务间接口（工单的预留创建/查询/续期/释放/消耗，质检的 QC-HOLD 入库/放行/报废）
     * 对无令牌、无权限用户令牌、全权限用户令牌、非法令牌一律 404 problem+json，且不转发到下游。
     */
    @Test void hidesInternalServicePathsAtGateway() {
        String all = token("http://localhost:8080", "manufacturing-api", Instant.now().plusSeconds(300),
            List.of("inventory:read", "inventory:write", "quality:read", "quality:write", "user:manage"));
        String noPerm = token("http://localhost:8080", "manufacturing-api", Instant.now().plusSeconds(300), List.of());
        Map<String, String> internal = new java.util.LinkedHashMap<>();
        internal.put("/api/inventory/reservations", "POST");                     // 创建预留
        internal.put("/api/inventory/reservations/R1", "GET");                   // 查询预留
        internal.put("/api/inventory/reservations/R1/renew", "POST");            // 续期
        internal.put("/api/inventory/reservations/R1/release", "POST");          // 释放
        internal.put("/api/inventory/reservations/R1/lines/L1/consume", "POST"); // 按行消耗
        internal.put("/api/inventory/qc-hold", "GET");                           // QC-HOLD 前缀
        internal.put("/api/inventory/qc-hold/receipts", "POST");                 // 入隔离
        internal.put("/api/inventory/qc-hold/releases", "POST");                 // 放行
        internal.put("/api/inventory/qc-hold/scraps", "POST");                   // 报废
        // 路径变体：尾斜杠、矩阵参数、重复斜杠、编码斜杠都不能绕过。
        internal.put("/api/inventory/reservations/", "POST");
        internal.put("/api/inventory/qc-hold;x=1/receipts", "POST");
        internal.put("/api/inventory//qc-hold/scraps", "POST");
        internal.put("/api/inventory/qc-hold%2Freleases", "POST");
        internal.forEach((path, method) -> {
            URI uri = URI.create("http://localhost:" + port + path);
            client().method(HttpMethod.valueOf(method)).uri(uri).exchange().expectStatus().isNotFound()
                .expectHeader().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON)
                .expectHeader().doesNotExist("WWW-Authenticate")
                .expectBody().jsonPath("$.type").isEqualTo("gateway-not-found").jsonPath("$.status").isEqualTo(404)
                .jsonPath("$.title").isEqualTo(GatewayProblemJsonTest.TITLES.get("gateway-not-found")).jsonPath("$.detail").exists().jsonPath("$.instance").exists().jsonPath("$.code").doesNotExist();
            for (String t : List.of(all, noPerm, "not-a-jwt"))
                client().method(HttpMethod.valueOf(method)).uri(uri).headers(h -> h.setBearerAuth(t)).exchange()
                    .expectStatus().isNotFound().expectBody().jsonPath("$.type").isEqualTo("gateway-not-found");
        });
        // instance 不含查询串。
        client().get().uri("/api/inventory/reservations/R1?x=1").exchange().expectStatus().isNotFound()
            .expectBody().jsonPath("$.instance").isEqualTo("/api/inventory/reservations/R1");
    }

    /**
     * 点段与反斜杠在全网关拒绝：明文、编码（%2e/%2E/.%2e/%2e%2e/%252e）与 ..;/ 变体都返回 404 gateway-not-found，
     * 即使目标是普通用户路径也不转发，防止下游规范化后落到内部路径。
     */
    @Test void rejectsDotSegmentsAndBackslashEverywhere() {
        String all = token("http://localhost:8080", "manufacturing-api", Instant.now().plusSeconds(300), List.of("inventory:read", "inventory:write"));
        List<String> paths = List.of(
            "/api/inventory/./qc-hold/receipts", "/api/inventory/x/../qc-hold/receipts",
            "/api/inventory/%2e/qc-hold/receipts", "/api/inventory/%2E%2E/inventory/qc-hold/scraps",
            "/api/inventory/x/%2e%2e/qc-hold/releases", "/api/inventory/x/.%2e/qc-hold/releases",
            "/api/inventory/x/%252e%252e/qc-hold/releases", "/api/inventory/x/..;/qc-hold/receipts",
            "/api/inventory/x/..;jsessionid=1/reservations", "/api/inventory/x%5C..%5Cqc-hold/receipts",
            "/api/inventory/./stock", "/api/x/../inventory/stock");
        for (String path : paths) {
            URI uri = URI.create("http://localhost:" + port + path);
            client().post().uri(uri).exchange().expectStatus().isNotFound()
                .expectBody().jsonPath("$.type").isEqualTo("gateway-not-found").jsonPath("$.title").isEqualTo(GatewayProblemJsonTest.TITLES.get("gateway-not-found"))
                .jsonPath("$.code").doesNotExist();
            client().get().uri(uri).headers(h -> h.setBearerAuth(all)).exchange().expectStatus().isNotFound()
                .expectBody().jsonPath("$.type").isEqualTo("gateway-not-found");
        }
    }

    /** 判定函数直接覆盖无法经 URI 发送的形式（明文反斜杠），以及不应误判的正常路径（含点的文件名式段）。 */
    @Test void dotSegmentDetection() {
        Assertions.assertTrue(InternalPathDenyFilter.hasDotSegmentOrBackslash("/api/inventory\\..\\qc-hold/receipts"));
        Assertions.assertTrue(InternalPathDenyFilter.hasDotSegmentOrBackslash("/api/a/..;/b"));
        Assertions.assertTrue(InternalPathDenyFilter.hasDotSegmentOrBackslash("/api/a/%2e%2E/b"));
        // 只解码一次：解码后仍含 % 即双重编码，拒绝。
        Assertions.assertTrue(InternalPathDenyFilter.hasDotSegmentOrBackslash("/api/a/%252e%252e/b"));
        Assertions.assertTrue(InternalPathDenyFilter.hasDotSegmentOrBackslash("/api/inventory/%252Fqc-hold"));
        Assertions.assertFalse(InternalPathDenyFilter.hasDotSegmentOrBackslash("/api/inventory/stock"));
        Assertions.assertFalse(InternalPathDenyFilter.hasDotSegmentOrBackslash("/api/materials/GR-60.v2"));
        Assertions.assertFalse(InternalPathDenyFilter.hasDotSegmentOrBackslash("/api/a/.../b"));
    }

    /** 内部路径清单不误伤正常用户路径：相邻前缀与常规库存接口仍按原规则授权并转发。 */
    @Test void userInventoryPathsStillRoute() {
        String all = token("http://localhost:8080", "manufacturing-api", Instant.now().plusSeconds(300), List.of("inventory:read", "inventory:write"));
        for (String path : List.of("/api/inventory/stock", "/api/inventory/locations", "/api/inventory/lots", "/api/inventory/ledger",
                "/api/inventory/stock/availability?materialCode=X", "/api/inventory/qc-holdings", "/api/inventory/reservationsx"))
            client().get().uri(path).headers(h -> h.setBearerAuth(all)).exchange().expectStatus().isOk()
                .expectBody(String.class).value(body -> Assertions.assertTrue(body.startsWith(path.substring(4).split("\\?")[0] + "|"), body));
        client().post().uri("/api/inventory/inbound").headers(h -> h.setBearerAuth(all)).exchange().expectStatus().isOk();
        // 普通库存路径未带令牌仍是 401（而非内部路径的 404）。
        client().get().uri("/api/inventory/stock").exchange().expectStatus().isUnauthorized();
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

    /**
     * 各角色权限取自仓库根目录的契约文件 contracts/auth/role-permissions.json（由 auth-service 的
     * RolePermissionContractTest 保证与 Role 枚举一致），网关模块不依赖认证服务代码，也不再手工复制权限清单（issue #28）。
     */
    static final Map<String, List<String>> ROLES = loadRolePermissions();
    static final List<String> OPERATOR = role("OPERATOR");
    static final List<String> QUALITY_INSPECTOR = role("QUALITY_INSPECTOR");
    static final List<String> ADMIN = role("ADMIN");

    /** 从模块目录向上查找契约文件；surefire 的工作目录是模块目录，IDE 中也可能是仓库根目录。 */
    static Map<String, List<String>> loadRolePermissions() {
        for (Path dir = Path.of("").toAbsolutePath(); dir != null; dir = dir.getParent()) {
            Path candidate = dir.resolve("contracts/auth/role-permissions.json");
            if (Files.isRegularFile(candidate)) {
                try {
                    return new ObjectMapper().readValue(candidate.toFile(), new TypeReference<Map<String, List<String>>>() { });
                } catch (java.io.IOException e) { throw new UncheckedIOException(e); }
            }
        }
        throw new IllegalStateException("未找到 contracts/auth/role-permissions.json");
    }
    /** 取契约中的角色权限；角色缺失直接失败，避免拿到空权限导致"拒绝"类断言误通过。 */
    static List<String> role(String name) {
        return Objects.requireNonNull(ROLES.get(name), "契约文件缺少角色 " + name);
    }

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

    static final List<String> PLANNER = role("PLANNER");
    static final List<String> VIEWER = role("VIEWER");

    /** 操作工下拉 /api/auth/operators：PLANNER、ADMIN 放行并去前缀转发；兼容期只有 workorder:write 也放行；VIEWER、只读令牌 403；匿名 401。 */
    @Test void operatorsDropdownRequiresDispatchPermission() {
        for (List<String> role : List.of(PLANNER, ADMIN))
            client().get().uri("/api/auth/operators").headers(h -> h.setBearerAuth(as(role))).exchange()
                .expectStatus().isOk().expectBody(String.class).value(b -> org.junit.jupiter.api.Assertions.assertTrue(b.startsWith("/auth/operators|")));
        String writerOnly = token("http://localhost:8080", "manufacturing-api", Instant.now().plusSeconds(300), List.of("workorder:write"));
        client().get().uri("/api/auth/operators").headers(h -> h.setBearerAuth(writerOnly)).exchange().expectStatus().isOk();
        String readOnly = token("http://localhost:8080", "manufacturing-api", Instant.now().plusSeconds(300), List.of("workorder:read"));
        client().get().uri("/api/auth/operators").headers(h -> h.setBearerAuth(readOnly)).exchange().expectStatus().isForbidden();
        client().get().uri("/api/auth/operators").headers(h -> h.setBearerAuth(as(VIEWER))).exchange().expectStatus().isForbidden();
        client().get().uri("/api/auth/operators").exchange().expectStatus().isUnauthorized();
    }

    /**
     * 派工路由：PLANNER（execution:dispatch）放行，OPERATOR 走 workorder:write 兼容也放行，VIEWER 403；
     * 开工需要 execution:report（或 workorder:write），PLANNER 403；任务列表 /api/operation-tasks 转发到工单服务。
     */
    @Test void dispatchRoutesUseExecutionPermissions() {
        String dispatch = "/api/workorders/WO1/operations/OP10/dispatch";
        client().post().uri(dispatch).headers(h -> h.setBearerAuth(as(PLANNER))).exchange()
            .expectStatus().isOk().expectBody(String.class).value(b -> org.junit.jupiter.api.Assertions.assertTrue(b.startsWith("/workorders/WO1/operations/OP10/dispatch|")));
        client().post().uri(dispatch).headers(h -> h.setBearerAuth(as(OPERATOR))).exchange().expectStatus().isOk();
        client().post().uri(dispatch).headers(h -> h.setBearerAuth(as(VIEWER))).exchange().expectStatus().isForbidden();
        // 只有 workorder:read 的令牌不能派工
        String readOnly = token("http://localhost:8080", "manufacturing-api", Instant.now().plusSeconds(300), List.of("workorder:read"));
        client().post().uri(dispatch).headers(h -> h.setBearerAuth(readOnly)).exchange().expectStatus().isForbidden();
        // PLANNER 不能修改工单：创建、下达、冻结、恢复、取消、完工、报工均 403
        for (String uri : List.of("/api/workorders", "/api/workorders/WO1/release", "/api/workorders/WO1/hold", "/api/workorders/WO1/resume",
                "/api/workorders/WO1/cancel", "/api/workorders/WO1/complete", "/api/workorders/WO1/operations/OP10/reports"))
            client().post().uri(uri).headers(h -> h.setBearerAuth(as(PLANNER))).exchange().expectStatus().isForbidden();
        client().post().uri("/api/workorders/WO1/operations/OP10/start").headers(h -> h.setBearerAuth(as(PLANNER))).exchange().expectStatus().isForbidden();
        client().post().uri("/api/workorders/WO1/operations/OP10/start").headers(h -> h.setBearerAuth(as(OPERATOR))).exchange().expectStatus().isOk();
        client().get().uri("/api/operation-tasks?station=ST-1").headers(h -> h.setBearerAuth(as(PLANNER))).exchange()
            .expectStatus().isOk().expectBody(String.class).value(b -> org.junit.jupiter.api.Assertions.assertTrue(b.startsWith("/operation-tasks|")));
    }
}
