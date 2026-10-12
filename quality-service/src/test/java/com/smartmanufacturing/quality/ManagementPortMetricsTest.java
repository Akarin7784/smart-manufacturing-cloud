package com.smartmanufacturing.quality;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.net.http.*;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.autoconfigure.actuate.observability.AutoConfigureObservability;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalManagementPort;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

/**
 * 验证监控端点的端口隔离：与 cloud profile 一样启用独立管理端口（此处随机端口），
 * 保护的行为是“管理端口匿名可抓 Prometheus 且带 application 标签、业务端口上的 actuator 仍需 metrics:read”。
 * 质检服务使用自有的过滤链（QualitySecurityConfiguration），不走通用安全链，因此单独覆盖这条安全边界。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = "management.server.port=0")
// Spring Boot 测试默认关闭指标导出，这里显式开启，才能真实渲染 /actuator/prometheus。
@AutoConfigureObservability(tracing = false)
@ActiveProfiles("test")
class ManagementPortMetricsTest {
    @LocalServerPort int serverPort;
    @LocalManagementPort int managementPort;
    // 不访问真实 JWKS：本测试只关心匿名请求，令牌校验由其他测试覆盖。
    @MockitoBean JwtDecoder decoder;

    private final HttpClient http = HttpClient.newHttpClient();

    private HttpResponse<String> get(int port, String path) throws Exception {
        return http.send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + path)).GET().build(),
            HttpResponse.BodyHandlers.ofString());
    }

    /** 管理端口：Prometheus 无需令牌即可抓取，且所有指标统一带 application 标签。 */
    @Test void prometheusIsAnonymousOnManagementPort() throws Exception {
        var response = get(managementPort, "/actuator/prometheus");
        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body()).contains("application=\"quality-service\"").contains("jvm_memory_used_bytes");
        // 启动即注册的质检指标（observability.md 第 5 节），运维据此对齐告警与看板。
        assertThat(response.body()).contains("mes_quality_outbox_pending{", "mes_quality_ncr_open{", "mes_quality_pending_reversal_expired_total{");
    }

    /**
     * 告警规则依赖的两条 Counter 序列启动即存在且为 0（尚未发生任何消费或过期）：
     * mes_quality_event_consumed_total{result="disposed_reversal_conflict"} 与 mes_quality_pending_reversal_expired_total。
     */
    @Test void alertCountersArePreRegisteredAtStartup() throws Exception {
        String body = get(managementPort, "/actuator/prometheus").body();
        assertThat(body).containsPattern("(?m)^mes_quality_event_consumed_total\\{[^}]*result=\"disposed_reversal_conflict\"[^}]*tag=\"DefectReversed\"[^}]*\\} 0\\.0$");
        assertThat(body).containsPattern("(?m)^mes_quality_pending_reversal_expired_total\\{application=\"quality-service\"\\} 0\\.0$");
    }

    /** 管理端口只放开 prometheus：其他非健康端点（如 info）仍需 metrics:read，防止通配放开。 */
    @Test void otherActuatorEndpointsStillRequireAuthority() throws Exception {
        assertThat(get(managementPort, "/actuator/info").statusCode()).isEqualTo(401);
        assertThat(get(managementPort, "/actuator/health/readiness").statusCode()).isEqualTo(200);
    }

    /** 业务端口：actuator 已迁走，匿名访问指标路径被安全链拒绝（401），不会泄露指标。 */
    @Test void prometheusIsNotAnonymousOnServerPort() throws Exception {
        assertThat(get(serverPort, "/actuator/prometheus").statusCode()).isEqualTo(401);
    }
}
