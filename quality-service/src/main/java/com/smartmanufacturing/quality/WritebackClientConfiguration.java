package com.smartmanufacturing.quality;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.cloud.client.loadbalancer.LoadBalanced;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;
import java.net.http.HttpClient;
import java.time.Clock;

/**
 * 库存回写客户端装配：绑定 {@link WritebackProperties}；没有其他 {@link InventoryQcHoldClient}（测试替身）时创建 HTTP 实现。
 * 未配置固定地址时使用 @LoadBalanced RestClient.Builder，经 Nacos 服务发现解析 http://inventory-service 与 http://auth-service，
 * 直连库存服务、不经网关（网关对 /api/inventory/qc-hold/** 返回 404）。写法与工单服务的 InventoryClientConfiguration 一致。
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(WritebackProperties.class)
class WritebackClientConfiguration {
    /** 带负载均衡的构建器，只供回写客户端使用（限定符 @LoadBalanced）。 */
    @Bean @LoadBalanced RestClient.Builder writebackLoadBalancedBuilder() { return RestClient.builder(); }

    @Bean @ConditionalOnMissingBean(InventoryQcHoldClient.class)
    InventoryQcHoldClient httpInventoryQcHoldClient(WritebackProperties props, @LoadBalanced ObjectProvider<RestClient.Builder> lb,
                                                    ObjectMapper mapper, Clock clock) {
        WritebackProperties.Client c = props.client();
        RestClient auth = builder(c, lb, c.authUrl()).baseUrl(c.authBase()).build();
        RestClient inventory = builder(c, lb, c.inventoryUrl()).baseUrl(c.inventoryBase()).build();
        return new HttpInventoryQcHoldClient(inventory, new QcHoldTokenProvider(auth, c, clock), mapper, c);
    }

    /** 固定地址用普通构建器，否则克隆负载均衡构建器；统一设置连接 / 读取超时（读取超时须小于执行租约）。 */
    private static RestClient.Builder builder(WritebackProperties.Client c, ObjectProvider<RestClient.Builder> lb, String fixedUrl) {
        RestClient.Builder b = fixedUrl == null || fixedUrl.isBlank() ? lb.getObject().clone() : RestClient.builder();
        var factory = new JdkClientHttpRequestFactory(HttpClient.newBuilder().connectTimeout(c.connectTimeout()).build());
        factory.setReadTimeout(c.readTimeout());
        return b.requestFactory(factory);
    }
}
