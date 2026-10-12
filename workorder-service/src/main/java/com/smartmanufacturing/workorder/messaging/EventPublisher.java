package com.smartmanufacturing.workorder.messaging;
/** 可替换的事件发送接口，数据库发件箱不依赖具体 RocketMQ SDK。 */
public interface EventPublisher {
    /** 发送持久化的原始消息体，只有 Broker 确认成功后才允许返回。 */
    void publish(String eventId, String payload) throws Exception;
}
