package com.smartmanufacturing.quality;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 消息配置：绑定 {@link MqProperties}，并在没有其他 {@link EventSender}（测试替身）时创建 RocketMQ 发送器。
 * NameServer 地址取 Nacos common.yml 的 {@code rocketmq.name-server}。生产者启动不连接 Broker，
 * NameServer 暂不可用时服务仍可启动，事件留在 outbox 中稍后补发。
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(MqProperties.class)
class MessagingConfiguration {
    @Bean(destroyMethod = "shutdown") @ConditionalOnMissingBean(EventSender.class)
    RocketMqEventSender rocketMqEventSender(MqProperties mq,
                                           @Value("${rocketmq.name-server:localhost:9876}") String nameServer,
                                           @Value("${quality.outbox.producer-group:PG_QUALITY_EVENT}") String group,
                                           @Value("${quality.outbox.send-timeout-ms:3000}") int timeout) throws Exception {
        return new RocketMqEventSender(nameServer, group, mq.qualityEventTopic(), timeout);
    }
}
