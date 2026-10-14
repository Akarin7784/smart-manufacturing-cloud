package com.smartmanufacturing.quality;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.web.client.RestClient;
import java.time.Clock;
import java.time.Duration;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

/** 回写客户端装配：固定地址时不使用负载均衡构建器；未配置地址时克隆负载均衡构建器（经服务发现）。 */
class WritebackClientConfigurationTest {
    private final WritebackClientConfiguration config = new WritebackClientConfiguration();

    private static WritebackProperties props(String inventoryUrl, String authUrl) {
        var client = new WritebackProperties.Client("quality-service", "s3cret", null, inventoryUrl, authUrl,
            null, null, Duration.ofSeconds(1), Duration.ofSeconds(3), null);
        return new WritebackProperties(null, null, null, null, null, null, null, null, client, null);
    }

    @SuppressWarnings("unchecked")
    private static ObjectProvider<RestClient.Builder> provider() {
        ObjectProvider<RestClient.Builder> lb = mock(ObjectProvider.class);
        when(lb.getObject()).thenReturn(RestClient.builder());
        return lb;
    }

    @Test void fixedUrlsBypassLoadBalancer() {
        var lb = provider();
        var client = config.httpInventoryQcHoldClient(props("http://localhost:8083", "http://localhost:8081"), lb, new ObjectMapper(), Clock.systemUTC());
        assertThat(client).isInstanceOf(HttpInventoryQcHoldClient.class);
        verify(lb, never()).getObject();
    }

    @Test void blankUrlsUseLoadBalancedBuilder() {
        var lb = provider();
        var client = config.httpInventoryQcHoldClient(props(" ", null), lb, new ObjectMapper(), Clock.systemUTC());
        assertThat(client).isNotNull();
        verify(lb, times(2)).getObject();
    }

    @Test void loadBalancedBuilderBeanIsPlainBuilder() {
        assertThat(config.writebackLoadBalancedBuilder()).isNotNull();
    }
}
