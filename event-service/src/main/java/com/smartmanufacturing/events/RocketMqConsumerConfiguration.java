package com.smartmanufacturing.events;

import com.smartmanufacturing.api.WorkOrderCreatedEvent;
import com.smartmanufacturing.messaging.RocketMqProperties;
import org.apache.rocketmq.client.consumer.DefaultMQPushConsumer;
import org.apache.rocketmq.client.consumer.listener.*;
import org.apache.rocketmq.common.consumer.ConsumeFromWhere;
import org.apache.rocketmq.remoting.protocol.heartbeat.MessageModel;
import org.slf4j.*;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.*;

/** 使用集群消费和持久化幂等处理，失败明确返回重试状态。 */
@Configuration
@ConditionalOnProperty(name = "messaging.rocketmq.enabled", havingValue = "true")
public class RocketMqConsumerConfiguration {
    private static final Logger LOG = LoggerFactory.getLogger(RocketMqConsumerConfiguration.class);

    /** 开发环境限制消费线程；新消费组从最早可用位置读取，重启使用 Broker 已保存偏移。 */
    @Bean(initMethod = "start", destroyMethod = "shutdown")
    DefaultMQPushConsumer workOrderConsumer(RocketMqProperties properties, EventInboxService inbox) throws Exception {
        DefaultMQPushConsumer consumer = new DefaultMQPushConsumer(properties.getConsumerGroup());
        consumer.setNamesrvAddr(properties.getNameServer());
        consumer.setVipChannelEnabled(false);
        consumer.setMessageModel(MessageModel.CLUSTERING);
        consumer.setConsumeFromWhere(ConsumeFromWhere.CONSUME_FROM_FIRST_OFFSET);
        consumer.setConsumeThreadMin(1);
        consumer.setConsumeThreadMax(2);
        consumer.setConsumeMessageBatchMaxSize(1);
        consumer.setMaxReconsumeTimes(6);
        consumer.subscribe(properties.getTopic(), WorkOrderCreatedEvent.TYPE);
        consumer.registerMessageListener((MessageListenerConcurrently) (messages, context) -> {
            for (var message : messages) {
                try {
                    inbox.accept(message.getBody());
                } catch (Exception e) {
                    LOG.warn("Message {} processing failed; delivery attempt {}", message.getMsgId(), message.getReconsumeTimes(), e);
                    // 不吞异常：Broker 按策略重试，达到上限后进入本消费组的死信队列。
                    return ConsumeConcurrentlyStatus.RECONSUME_LATER;
                }
            }
            return ConsumeConcurrentlyStatus.CONSUME_SUCCESS;
        });
        return consumer;
    }
}
