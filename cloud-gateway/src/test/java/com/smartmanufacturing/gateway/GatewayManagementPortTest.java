package com.smartmanufacturing.gateway;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.autoconfigure.actuate.observability.AutoConfigureObservability;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalManagementPort;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.security.oauth2.jwt.ReactiveJwtDecoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.reactive.server.WebTestClient;

/**
 * 验证网关（WebFlux）的监控端点隔离，与 cloud profile 一致：独立管理端口 + 业务端口附加 /livez、/readyz。
 * 保护的行为：8080（对外发布）上不能匿名读取指标，管理端口（仅内网）可匿名抓取且带 application 标签。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = {"management.server.port=0", "management.endpoint.health.probes.add-additional-paths=true"})
// Spring Boot 测试默认关闭指标导出，这里显式开启，才能真实渲染 /actuator/prometheus。
@AutoConfigureObservability(tracing = false)
@ActiveProfiles("local")
class GatewayManagementPortTest {
    @LocalServerPort int serverPort;
    @LocalManagementPort int managementPort;
    // 不访问真实 JWKS：本测试只关心匿名请求。
    @MockitoBean ReactiveJwtDecoder decoder;

    private WebTestClient client(int port) { return WebTestClient.bindToServer().baseUrl("http://localhost:" + port).build(); }

    /** 管理端口匿名抓取 Prometheus，指标带网关的 application 标签。 */
    @Test void prometheusIsAnonymousOnManagementPort() {
        client(managementPort).get().uri("/actuator/prometheus").exchange()
            .expectStatus().isOk()
            .expectBody(String.class).value(body -> org.assertj.core.api.Assertions.assertThat(body).contains("application=\"cloud-gateway\""));
    }

    /** 管理端口上只放开 prometheus，其他端点仍需 metrics:read。 */
    @Test void otherManagementEndpointsRequireAuthority() {
        client(managementPort).get().uri("/actuator/info").exchange().expectStatus().isUnauthorized();
    }

    /** 业务端口：指标路径匿名访问返回 401；附加健康路径 /readyz 可匿名访问，供宿主机确认网关存活。 */
    @Test void serverPortHidesMetricsButKeepsReadiness() {
        client(serverPort).get().uri("/actuator/prometheus").exchange().expectStatus().isUnauthorized();
        client(serverPort).get().uri("/readyz").exchange().expectStatus().isOk();
    }
}
