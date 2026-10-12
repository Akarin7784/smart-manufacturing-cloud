package com.smartmanufacturing.workorder;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.MDC;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import java.sql.Timestamp;
import java.time.Clock;
import java.util.Map;
import java.util.UUID;

/**
 * 审计日志与事务性发件箱写入。两者都必须在调用方的业务事务内执行，保证“状态变了就一定有日志和事件，反之亦然”。
 * 事件中继（读取 published_at 为空的记录发布到 RocketMQ）随运维分支 ops/rocketmq 落地，本服务当前只负责入库。
 */
@Component
class WorkOrderJournal {
    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;
    private final Clock clock;
    WorkOrderJournal(JdbcTemplate jdbc, ObjectMapper mapper, Clock clock) { this.jdbc = jdbc; this.mapper = mapper; this.clock = clock; }

    /** 追加一条状态转换审计；requestId 取自当前请求的关联标识，便于与网关日志对照。 */
    void statusChanged(String orderId, String from, String to, String reason, String note, String operator) {
        jdbc.update("insert into work_order_status_log (id, order_id, from_status, to_status, reason, note, operator, request_id, created_at) values (?, ?, ?, ?, ?, ?, ?, ?, ?)",
            UUID.randomUUID().toString(), orderId, from, to, reason, note, operator, MDC.get("requestId"), Timestamp.from(clock.instant()));
    }

    /**
     * 写入带投递目标的发件箱事件（topic / tag 由调用方从 {@link MqProperties} 读取，不在代码中写死）。
     * payload 统一补充 eventId、occurredAt；返回事件号。
     */
    String routedEvent(String orderId, String eventType, String topic, String tag, Map<String, Object> payload) {
        String eventId = UUID.randomUUID().toString();
        java.time.Instant now = clock.instant();
        var body = new java.util.LinkedHashMap<String, Object>();
        body.put("eventId", eventId);
        body.putAll(payload);
        body.put("occurredAt", now.toString());
        try {
            jdbc.update("insert into outbox_event (event_id, aggregate_id, event_type, payload, occurred_at, topic, tag) values (?, ?, ?, ?, ?, ?, ?)",
                eventId, orderId, eventType, mapper.writeValueAsString(body), Timestamp.from(now), topic, tag);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("事件序列化失败", e);
        }
        return eventId;
    }

    /** 写入发件箱事件；payload 统一包含 eventId 与 orderId，消费者按 eventId 幂等。返回事件号。 */
    String event(String orderId, String type, Map<String, Object> payload) {
        String eventId = UUID.randomUUID().toString();
        var body = new java.util.LinkedHashMap<String, Object>();
        body.put("eventId", eventId);
        body.put("orderId", orderId);
        body.putAll(payload);
        try {
            jdbc.update("insert into outbox_event (event_id, aggregate_id, event_type, payload, occurred_at) values (?, ?, ?, ?, ?)",
                eventId, orderId, type, mapper.writeValueAsString(body), Timestamp.from(clock.instant()));
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("事件序列化失败", e);
        }
        return eventId;
    }
}
