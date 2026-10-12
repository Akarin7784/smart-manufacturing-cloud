package com.smartmanufacturing.workorder;

/** 事件投递端口：outbox 中继通过它把事件发到消息中间件。生产实现为 {@link RocketMqEventSender}，测试中可替换。 */
public interface EventSender {
    /**
     * 同步发送一条消息，返回即表示 Broker 已确认；失败抛出异常，由中继退避重试。
     * @param topic   Topic（取自 outbox 行，写入时来自 Nacos manufacturing.mq.topics）
     * @param tag     Tag
     * @param key     消息键（工单号）
     * @param eventId 事件 ID，重发时不变
     * @param payload 事件 JSON
     */
    void send(String topic, String tag, String key, String eventId, String payload) throws Exception;
}
