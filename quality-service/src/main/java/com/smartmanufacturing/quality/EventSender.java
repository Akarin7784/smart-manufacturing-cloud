package com.smartmanufacturing.quality;

/**
 * 事件投递端口：outbox 发布任务通过它把事件发到消息中间件。生产实现为 {@link RocketMqEventSender}，测试中替换为内存实现。
 */
public interface EventSender {
    /**
     * 同步发送一条消息，返回即表示 Broker 已确认；失败抛出异常，由发布任务退避重试。
     * @param tag     消息 tag（事件类型）
     * @param key     消息键（ncrNo）
     * @param eventId 事件 ID，补发时不变
     * @param payload 事件 JSON
     */
    void send(String tag, String key, String eventId, String payload) throws Exception;
}
