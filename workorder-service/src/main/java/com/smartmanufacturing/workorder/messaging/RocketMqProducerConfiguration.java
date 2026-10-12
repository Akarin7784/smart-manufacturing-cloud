package com.smartmanufacturing.workorder.messaging;

import com.smartmanufacturing.api.WorkOrderCreatedEvent;
import com.smartmanufacturing.messaging.RocketMqProperties;
import java.nio.charset.StandardCharsets;
import org.apache.rocketmq.client.producer.*;
import org.apache.rocketmq.common.message.Message;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.*;

/** 显式管理官方客户端生命周期，避免引入其他 Boot 版本的自动配置。 */
@Configuration
@ConditionalOnProperty(name = "messaging.rocketmq.enabled", havingValue = "true")
public class RocketMqProducerConfiguration {
    /** 客户端启动只连接指定 NameServer，进程退出时释放网络线程。 */
    @Bean(initMethod = "start", destroyMethod = "shutdown")
    DefaultMQProducer workOrderProducer(RocketMqProperties properties) {
        DefaultMQProducer producer = new DefaultMQProducer(properties.getProducerGroup());
        producer.setNamesrvAddr(properties.getNameServer());
        producer.setVipChannelEnabled(false);
        producer.setSendMsgTimeout(properties.getSendTimeoutMs());
        // 重试交给持久化 Outbox，避免 SDK 内部多次重试使单次处理超过租约。
        producer.setRetryTimesWhenSendFailed(0);
        producer.setRetryAnotherBrokerWhenNotStoreOK(false);
        return producer;
    }

    /** 事件 ID 用作消息 Keys；超时或非 SEND_OK 都进入发件箱重试流程。 */
    @Bean EventPublisher eventPublisher(DefaultMQProducer producer, RocketMqProperties properties) {
        return (eventId, payload) -> {
            Message message = new Message(properties.getTopic(), WorkOrderCreatedEvent.TYPE, eventId,
                payload.getBytes(StandardCharsets.UTF_8));
            SendResult result = producer.send(message, properties.getSendTimeoutMs());
            if (result.getSendStatus() != SendStatus.SEND_OK)
                throw new IllegalStateException("Broker 未确认消息持久化: " + result.getSendStatus());
        };
    }
}
