package com.smartmanufacturing.workorder;

import org.apache.rocketmq.client.producer.DefaultMQProducer;
import org.apache.rocketmq.client.producer.SendResult;
import org.apache.rocketmq.client.producer.SendStatus;
import org.apache.rocketmq.common.message.Message;
import java.nio.charset.StandardCharsets;

/**
 * RocketMQ 投递实现（与 #18 库存服务一致）：remoting 协议 DefaultMQProducer 直连 NameServer，同步发送，只有 SEND_OK 视为成功。
 * 发送超时较短（默认 3 秒）且客户端不重试，避免中继领取事务长时间持有行锁与连接；重试统一交给 outbox 退避。
 */
public class RocketMqEventSender implements EventSender {
    private final DefaultMQProducer producer;

    public RocketMqEventSender(String nameServer, String producerGroup, int sendTimeoutMillis) throws Exception {
        this.producer = new DefaultMQProducer(producerGroup);
        producer.setNamesrvAddr(nameServer);
        // 独立实例名：同一 JVM 中多个 Spring 上下文（如测试）各自创建生产者时，避免共用客户端实例导致“生产者组已存在”
        producer.setInstanceName(producerGroup + "-" + java.util.UUID.randomUUID());
        producer.setSendMsgTimeout(sendTimeoutMillis);
        producer.setRetryTimesWhenSendFailed(0);
        producer.start();
    }

    @Override
    public void send(String topic, String tag, String key, String eventId, String payload) throws Exception {
        Message message = new Message(topic, tag, key, payload.getBytes(StandardCharsets.UTF_8));
        // eventId 同时放在消息属性中，消费端无需解析消息体即可去重
        message.putUserProperty("eventId", eventId);
        SendResult result = producer.send(message);
        if (result.getSendStatus() != SendStatus.SEND_OK)
            throw new IllegalStateException("RocketMQ 发送状态 " + result.getSendStatus());
    }

    /** 关闭生产者，由 Spring 容器在销毁 Bean 时调用。 */
    public void shutdown() { producer.shutdown(); }
}
