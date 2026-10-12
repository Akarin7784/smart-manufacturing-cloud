package com.smartmanufacturing.inventory;

import org.apache.rocketmq.client.producer.DefaultMQProducer;
import org.apache.rocketmq.client.producer.SendResult;
import org.apache.rocketmq.client.producer.SendStatus;
import org.apache.rocketmq.common.message.Message;
import java.nio.charset.StandardCharsets;

/**
 * RocketMQ 投递实现：使用 remoting 协议的 DefaultMQProducer 直连 NameServer（与 #13 的 namesrv + broker 部署匹配，不依赖 Proxy）。
 * 同步发送，只有 SEND_OK 视为成功；其他状态（刷盘/同步超时）按失败处理并由 outbox 重试，宁可重复不丢失。
 * topic 来自 Nacos {@code manufacturing.mq.topics.inventory-reservation}。
 */
public class RocketMqEventSender implements EventSender {
    private final DefaultMQProducer producer;
    private final String topic;

    public RocketMqEventSender(String nameServer, String producerGroup, String topic, int sendTimeoutMillis) throws Exception {
        this.topic = topic;
        this.producer = new DefaultMQProducer(producerGroup);
        producer.setNamesrvAddr(nameServer);
        producer.setSendMsgTimeout(sendTimeoutMillis);
        // 重试交给 outbox（带退避且可观测），客户端内部不再重试，避免一次调度阻塞过久。
        producer.setRetryTimesWhenSendFailed(0);
        producer.start();
    }

    @Override
    public void send(String tag, String key, String eventId, String payload) throws Exception {
        Message message = new Message(topic, tag, key, payload.getBytes(StandardCharsets.UTF_8));
        // eventId 同时放在消息属性中，消费端无需解析消息体即可去重。
        message.putUserProperty("eventId", eventId);
        SendResult result = producer.send(message);
        if (result.getSendStatus() != SendStatus.SEND_OK)
            throw new IllegalStateException("RocketMQ 发送状态 " + result.getSendStatus());
    }

    /** 关闭生产者，由 Spring 容器在销毁 Bean 时调用（见 MessagingConfiguration）。 */
    public void shutdown() { producer.shutdown(); }
}
