package com.smartmanufacturing.workorder;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.cloud.client.loadbalancer.LoadBalanced;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestClient;

import java.time.Clock;

/**
 * 服务身份公共装配（#74）：客户端凭据（manufacturing.workorder.inventory.client-id / client-secret）与负载均衡构建器，
 * 供库存客户端与质量服务查询共用。质量服务查询令牌始终装配（关闭工单不依赖库存适配器模式）；密钥未配置时申请令牌失败，关闭返回 503。
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(InventoryClientProperties.class)
class ServiceClientConfiguration {
    /** 带负载均衡的构建器，只供服务身份客户端使用（限定符 @LoadBalanced）。 */
    @Bean @LoadBalanced RestClient.Builder inventoryLoadBalancedBuilder() { return RestClient.builder(); }

    /** 关闭工单查 NCR 的服务令牌（scope=quality:read，受众 quality-service）。 */
    @Bean QualityServiceToken qualityServiceToken(InventoryClientProperties props, @LoadBalanced ObjectProvider<RestClient.Builder> lb, Clock clock) {
        RestClient auth = InventoryClientConfiguration.builder(props, lb, props.authUrl()).baseUrl(props.authBase()).build();
        return new QualityServiceToken(new ServiceTokenProvider(auth, props, clock, QualityServiceToken.SCOPE));
    }
}
