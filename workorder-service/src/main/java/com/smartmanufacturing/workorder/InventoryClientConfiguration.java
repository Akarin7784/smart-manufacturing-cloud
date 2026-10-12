package com.smartmanufacturing.workorder;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.cloud.client.loadbalancer.LoadBalanced;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;
import java.net.http.HttpClient;
import java.time.Clock;

/**
 * 库存客户端装配：仅当 manufacturing.workorder.material-reservation=inventory 时生效（缺省 local 替身）。
 * 未配置固定地址时使用 @LoadBalanced RestClient.Builder，经 Nacos 服务发现解析 http://inventory-service 与 http://auth-service。
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(InventoryClientProperties.class)
@ConditionalOnProperty(name = "manufacturing.workorder.material-reservation", havingValue = "inventory")
class InventoryClientConfiguration {
    /** 带负载均衡的构建器，只供库存客户端使用（限定符 @LoadBalanced）。 */
    @Bean @LoadBalanced RestClient.Builder inventoryLoadBalancedBuilder() { return RestClient.builder(); }

    @Bean InventoryReservationClient inventoryReservationClient(InventoryClientProperties props, @LoadBalanced ObjectProvider<RestClient.Builder> lb,
                                                                ObjectMapper mapper, Clock clock, InventoryClientMetrics metrics) {
        RestClient auth = builder(props, lb, props.authUrl()).baseUrl(props.authBase()).build();
        RestClient inventory = builder(props, lb, props.inventoryUrl()).baseUrl(props.inventoryBase()).build();
        return new InventoryReservationClient(inventory, new ServiceTokenProvider(auth, props, clock), mapper, metrics);
    }

    /** 固定地址用普通构建器，否则克隆负载均衡构建器；统一设置连接 / 读取超时。 */
    private static RestClient.Builder builder(InventoryClientProperties props, ObjectProvider<RestClient.Builder> lb, String fixedUrl) {
        RestClient.Builder b = fixedUrl == null || fixedUrl.isBlank() ? lb.getObject().clone() : RestClient.builder();
        var factory = new JdkClientHttpRequestFactory(HttpClient.newBuilder().connectTimeout(props.connectTimeout()).build());
        factory.setReadTimeout(props.readTimeout());
        return b.requestFactory(factory);
    }
}
