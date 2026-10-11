package com.smartmanufacturing.gateway;
import java.net.*;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.*;
import org.springframework.test.web.reactive.server.WebTestClient;
/** 通过独立 HTTP 上游验证网关实际转发与请求头处理。 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("local")
class GatewayRoutingTest {
    static final HttpServer upstream;
    static {
        try {
            upstream = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            upstream.createContext("/", exchange -> {
                String body = exchange.getRequestURI().getPath() + "|" + exchange.getRequestHeaders().getFirst("X-Request-Id");
                byte[] bytes = body.getBytes(java.nio.charset.StandardCharsets.UTF_8);
                exchange.sendResponseHeaders(200, bytes.length);
                try (var out = exchange.getResponseBody()) { out.write(bytes); }
            });
            upstream.start();
        } catch (Exception e) { throw new ExceptionInInitializerError(e); }
    }
    @DynamicPropertySource static void routes(DynamicPropertyRegistry registry) {
        registry.add("DEVICE_SERVICE_URL", () -> "http://127.0.0.1:" + upstream.getAddress().getPort());
        registry.add("WORKORDER_SERVICE_URL", () -> "http://127.0.0.1:" + upstream.getAddress().getPort());
    }
    @LocalServerPort int port;
    @AfterAll static void stop() { upstream.stop(0); }
    /** 验证前缀被移除，客户端标识被网关替换。 */
    @Test void stripsPrefixAndReplacesUntrustedRequestId() {
        WebTestClient.bindToServer().baseUrl("http://localhost:" + port).build()
            .get().uri("/api/devices/D001").header("X-Request-Id", "client-value").exchange()
            .expectStatus().isOk().expectHeader().exists("X-Request-Id")
            .expectBody(String.class).value(body -> {
                Assertions.assertTrue(body.startsWith("/devices/D001|"));
                Assertions.assertFalse(body.contains("client-value"));
            });
    }
    /** 验证工单路径被转发到正确上游。 */
    @Test void routesWorkorders() {
        WebTestClient.bindToServer().baseUrl("http://localhost:" + port).build()
            .get().uri("/api/workorders").exchange().expectStatus().isOk()
            .expectBody(String.class).value(body -> Assertions.assertTrue(body.startsWith("/workorders|")));
    }
}
