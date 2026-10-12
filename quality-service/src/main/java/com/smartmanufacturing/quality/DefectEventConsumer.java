package com.smartmanufacturing.quality;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.rocketmq.client.consumer.DefaultMQPushConsumer;
import org.apache.rocketmq.client.consumer.listener.ConsumeConcurrentlyStatus;
import org.apache.rocketmq.client.consumer.listener.MessageListenerConcurrently;
import org.apache.rocketmq.common.consumer.ConsumeFromWhere;
import org.apache.rocketmq.common.message.MessageExt;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.SmartLifecycle;
import org.springframework.stereotype.Component;
import java.nio.charset.StandardCharsets;

/**
 * 不良品事件消费入口：以 remoting 协议的 DefaultMQPushConsumer 订阅 {@code mes-workorder-quality}
 * （tag DefectReported || DefectReversed，消费组 CG_QUALITY_DEFECT，集群模式），每条消息交给 {@link DefectEventHandler}。
 * <ul>
 *   <li>处理成功（含重复、暂存、冲突）即 ACK（CONSUME_SUCCESS）；不可恢复的坏消息留档后也 ACK（见 {@link #consume}）；
 *       其他异常返回 RECONSUME_LATER，由 Broker 退避重投，超过最大重投次数进入死信队列 %DLQ%CG_QUALITY_DEFECT。</li>
 *   <li>eventId 优先取消息体，其次取消息属性 eventId（工单服务两处都写）。</li>
 *   <li>新消费组首次启动从最早的保留消息开始消费（CONSUME_FROM_FIRST_OFFSET）：上线前已产生的不良不会丢，重复由 eventId 去重。</li>
 *   <li>随容器生命周期启动与关闭（SmartLifecycle），保证处理器等 Bean 就绪后才开始拉取；测试中通过
 *       {@code quality.consumer.enabled=false} 关闭。</li>
 * </ul>
 */
@Component
@ConditionalOnProperty(prefix = "quality.consumer", name = "enabled", havingValue = "true", matchIfMissing = true)
public class DefectEventConsumer implements SmartLifecycle {
    private static final Logger log = LoggerFactory.getLogger(DefectEventConsumer.class);

    private final DefectEventHandler handler;
    private final InvalidEventRecorder recorder;
    private final QualityMetrics metrics;
    private final MqProperties mq;
    private final ObjectMapper mapper;
    private final String nameServer;
    private final int threads;
    private DefaultMQPushConsumer consumer;
    private volatile boolean running;

    public DefectEventConsumer(DefectEventHandler handler, InvalidEventRecorder recorder, QualityMetrics metrics, MqProperties mq, ObjectMapper mapper,
                               @Value("${rocketmq.name-server:localhost:9876}") String nameServer,
                               @Value("${quality.consumer.consume-threads:4}") int threads) {
        this.handler = handler; this.recorder = recorder; this.metrics = metrics; this.mq = mq; this.mapper = mapper; this.nameServer = nameServer; this.threads = threads;
    }

    @Override
    public void start() {
        try {
            consumer = new DefaultMQPushConsumer(mq.defectConsumerGroup());
            consumer.setNamesrvAddr(nameServer);
            consumer.setConsumeFromWhere(ConsumeFromWhere.CONSUME_FROM_FIRST_OFFSET);
            consumer.setConsumeThreadMin(threads);
            consumer.setConsumeThreadMax(threads);
            // 每次只投递一条：一条消息一个事务，失败只重投这一条。
            consumer.setConsumeMessageBatchMaxSize(1);
            consumer.subscribe(mq.workorderQualityTopic(), mq.defectReportedTag() + " || " + mq.defectReversedTag());
            consumer.registerMessageListener((MessageListenerConcurrently) (messages, context) -> {
                for (MessageExt message : messages) {
                    if (!consume(message)) return ConsumeConcurrentlyStatus.RECONSUME_LATER;
                }
                return ConsumeConcurrentlyStatus.CONSUME_SUCCESS;
            });
            consumer.start();
            running = true;
            log.info("已订阅 {}（{}），消费组 {}", mq.workorderQualityTopic(), mq.defectReportedTag() + " || " + mq.defectReversedTag(),
                mq.defectConsumerGroup());
        } catch (Exception e) {
            throw new IllegalStateException("启动不良品事件消费者失败", e);
        }
    }

    /**
     * 处理一条消息并计数。
     * <ul>
     *   <li>成功（含重复、暂存、冲突）：ACK。</li>
     *   <li>不可恢复的坏消息（JSON 解析失败、缺 eventId、缺字段等 {@link DefectEventHandler.InvalidEventException}）：
     *       记录到 invalid_event、计 result=invalid 后直接 ACK，不再走 16 次重投（#30）；记录写入失败则重投。</li>
     *   <li>其他异常（数据库、网络等可恢复错误）：计 error，返回 RECONSUME_LATER 重投。</li>
     * </ul>
     * @return true 表示可以 ACK
     */
    boolean consume(MessageExt message) {
        String tag = message.getTags() == null ? "" : message.getTags();
        try {
            JsonNode body;
            try {
                body = mapper.readTree(new String(message.getBody(), StandardCharsets.UTF_8));
            } catch (JsonProcessingException e) {
                throw new DefectEventHandler.InvalidEventException("消息体不是合法 JSON：" + e.getOriginalMessage());
            }
            if (body == null || !body.isObject()) throw new DefectEventHandler.InvalidEventException("消息体不是 JSON 对象");
            String eventId = body.hasNonNull("eventId") ? body.get("eventId").asText() : message.getUserProperty("eventId");
            DefectEventHandler.Result result = handler.handle(tag, eventId, body);
            metrics.consumed(tag, result.metric());
            return true;
        } catch (DefectEventHandler.InvalidEventException invalid) {
            try {
                recorder.record(message.getMsgId(), tag, message.getBody(), invalid.getMessage());
            } catch (RuntimeException e) {
                metrics.consumed(tag, "error");
                log.error("坏消息留档失败，稍后重投：msgId={}, tag={}", message.getMsgId(), tag, e);
                return false;
            }
            metrics.consumed(tag, QualityMetrics.INVALID);
            log.error("不良品事件不合法，已留档并 ACK（不重投）：msgId={}, tag={}, 原因={}", message.getMsgId(), tag, invalid.getMessage());
            return true;
        } catch (Exception e) {
            metrics.consumed(tag, "error");
            log.error("不良品事件处理失败，稍后重投：msgId={}, tag={}, reconsumeTimes={}", message.getMsgId(), tag, message.getReconsumeTimes(), e);
            return false;
        }
    }

    @Override
    public void stop() {
        running = false;
        if (consumer != null) consumer.shutdown();
    }

    @Override
    public boolean isRunning() { return running; }
}
