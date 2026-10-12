package com.smartmanufacturing.auth;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.net.http.*;
import java.nio.file.*;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.autoconfigure.actuate.observability.AutoConfigureObservability;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalManagementPort;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.*;

/**
 * 验证认证服务（独立安全链 AuthSecurityConfiguration）的监控端点端口隔离，与 cloud profile 一样启用独立管理端口（此处随机端口）。
 * 保护的行为：管理端口匿名可抓 Prometheus；管理端口的其他 actuator 端点仍需 metrics:read；业务端口上的指标不允许匿名读取。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = "management.server.port=0")
// Spring Boot 测试默认关闭指标导出，这里显式开启，才能真实渲染 /actuator/prometheus。
@AutoConfigureObservability(tracing = false)
@ActiveProfiles("test")
class AuthManagementPortMetricsTest {
    static final Path directory;
    static {
        // 签名私钥写到临时目录，避免污染工作区或与其他测试共用密钥文件。
        try { directory = Files.createTempDirectory("manufacturing-auth-metrics-test-"); }
        catch (Exception e) { throw new ExceptionInInitializerError(e); }
    }
    @DynamicPropertySource static void keyPath(DynamicPropertyRegistry registry) {
        registry.add("auth.private-key-path", () -> directory.resolve("signing-key.json").toString());
    }

    @LocalServerPort int serverPort;
    @LocalManagementPort int managementPort;
    private final HttpClient http = HttpClient.newHttpClient();

    /** 不带任何凭据的匿名请求。 */
    private HttpResponse<String> get(int port, String path) throws Exception {
        return http.send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + path)).GET().build(),
            HttpResponse.BodyHandlers.ofString());
    }

    /** 管理端口：Prometheus 无需令牌即可抓取，且指标带 application 标签。 */
    @Test void prometheusIsAnonymousOnManagementPort() throws Exception {
        var response = get(managementPort, "/actuator/prometheus");
        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body()).contains("application=\"auth-service\"");
    }

    /** 管理端口只放开 prometheus：info 等其他端点匿名访问仍返回 401，防止通配放开。 */
    @Test void infoRequiresAuthorityOnManagementPort() throws Exception {
        assertThat(get(managementPort, "/actuator/info").statusCode()).isEqualTo(401);
    }

    /** 业务端口：匿名访问指标路径被安全链拒绝（401），不会泄露指标。 */
    @Test void prometheusIsNotAnonymousOnServerPort() throws Exception {
        assertThat(get(serverPort, "/actuator/prometheus").statusCode()).isEqualTo(401);
    }
}
