package com.smartmanufacturing.workorder;
import org.apache.rocketmq.client.consumer.DefaultMQPushConsumer;
import org.apache.rocketmq.client.consumer.listener.ConsumeConcurrentlyStatus;
import org.apache.rocketmq.client.consumer.listener.MessageListenerConcurrently;
import org.apache.rocketmq.common.message.MessageExt;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.SmartLifecycle;
import org.springframework.stereotype.Component;
import java.nio.charset.StandardCharsets;

/**
 * 订阅库存预留事件（remoting 协议 DefaultMQPushConsumer，与 outbox 中继使用同一 rocketmq-client）。
 * Topic、Tag、消费组均取自 Nacos common.yml 的 manufacturing.mq.*（消费组 CG_WORKORDER_RESERVATION）。
 * 仅在接入真实库存（material-reservation=inventory）且未关闭 manufacturing.workorder.reservation-events.enabled 时启动。
 * 处理异常返回 RECONSUME_LATER 由 Broker 重投；格式非法的事件在 handler 中计数后丢弃（ACK），不无限重投。
 */
@Component
@ConditionalOnProperty(name = "manufacturing.workorder.material-reservation", havingValue = "inventory")
class ReservationEventConsumer implements SmartLifecycle {
    private static final Logger LOG = LoggerFactory.getLogger(ReservationEventConsumer.class);
    private final ReservationEventHandler handler;
    private final String nameServer, topic, group;
    private final boolean enabled;
    private DefaultMQPushConsumer consumer;

    ReservationEventConsumer(ReservationEventHandler handler, MqProperties mq,
                             @Value("${rocketmq.name-server:localhost:9876}") String nameServer,
                             @Value("${manufacturing.mq.consumer-groups.workorder-reservation:CG_WORKORDER_RESERVATION}") String group,
                             @Value("${manufacturing.workorder.reservation-events.enabled:true}") boolean enabled) {
        this.handler = handler; this.nameServer = nameServer; this.group = group; this.enabled = enabled;
        this.topic = mq.topic("inventory-reservation");
    }

    @Override public void start() {
        if (!enabled) return;
        try {
            consumer = new DefaultMQPushConsumer(group);
            consumer.setNamesrvAddr(nameServer);
            consumer.setInstanceName(group + "-" + java.util.UUID.randomUUID());
            consumer.subscribe(topic, String.join(" || ", handler.tags()));
            consumer.registerMessageListener((MessageListenerConcurrently) (msgs, ctx) -> {
                for (MessageExt m : msgs) {
                    try { handler.handle(m.getTags(), new String(m.getBody(), StandardCharsets.UTF_8)); }
                    catch (RuntimeException e) {
                        LOG.warn("处理库存预留事件失败，稍后重投：msgId={} tag={}", m.getMsgId(), m.getTags(), e);
                        return ConsumeConcurrentlyStatus.RECONSUME_LATER;
                    }
                }
                return ConsumeConcurrentlyStatus.CONSUME_SUCCESS;
            });
            consumer.start();
            LOG.info("已订阅库存预留事件 topic={} group={}", topic, group);
        } catch (Exception e) {
            throw new IllegalStateException("启动库存预留事件消费者失败", e);
        }
    }

    @Override public void stop() { if (consumer != null) consumer.shutdown(); consumer = null; }
    @Override public boolean isRunning() { return consumer != null; }
}
