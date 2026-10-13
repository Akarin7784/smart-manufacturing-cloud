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
    private final MqProperties mq;
    private final ReportingMetrics metrics;
    WorkOrderJournal(JdbcTemplate jdbc, ObjectMapper mapper, Clock clock, MqProperties mq, ReportingMetrics metrics) {
        this.jdbc = jdbc; this.mapper = mapper; this.clock = clock; this.mq = mq; this.metrics = metrics;
    }

    /** 生命周期事件类型 → Nacos manufacturing.mq.tags 的配置键（均投递到 workorder-event Topic）。 */
    private static final Map<String, String> LIFECYCLE_TAGS = Map.of(
        "WorkOrderReleased", "workorder-released",
        "WorkOrderHeld", "workorder-held",
        "WorkOrderResumed", "workorder-resumed",
        "WorkOrderHoldReasonCleared", "workorder-hold-reason-cleared",
        "WorkOrderCancelled", "workorder-cancelled");

    /** 追加一条状态转换审计；requestId 取自当前请求的关联标识，便于与网关日志对照。 */
    void statusChanged(String orderId, String from, String to, String reason, String note, String operator) {
        jdbc.update("insert into work_order_status_log (id, order_id, from_status, to_status, reason, note, operator, request_id, created_at) values (?, ?, ?, ?, ?, ?, ?, ?, ?)",
            UUID.randomUUID().toString(), orderId, from, to, reason, note, operator, MDC.get("requestId"), DbTimes.ts(clock.instant()));
        // 状态真正变化时计入 mes_workorder_status_transition_total{from,to}（同状态的审计，如追加冻结原因，不计）
        if (!from.equals(to)) metrics.transitionAfterCommit(from, to);
    }

    /**
     * 写入带投递目标的发件箱事件（topic / tag 由调用方从 {@link MqProperties} 读取，不在代码中写死）。
     * payload 统一补充 eventId、occurredAt；返回事件号。
     */
    String routedEvent(String orderId, String eventType, String topic, String tag, Map<String, Object> payload) {
        String eventId = UUID.randomUUID().toString();
        // 先截断到毫秒：payload 中的 occurredAt 与库中 occurred_at 保持一致
        java.time.Instant now = DbTimes.millis(clock.instant());
        var body = new java.util.LinkedHashMap<String, Object>();
        body.put("eventId", eventId);
        body.putAll(payload);
        body.put("occurredAt", now.toString());
        try {
            jdbc.update("insert into outbox_event (event_id, aggregate_id, event_type, payload, occurred_at, topic, tag) values (?, ?, ?, ?, ?, ?, ?)",
                eventId, orderId, eventType, mapper.writeValueAsString(body), DbTimes.ts(now), topic, tag);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("事件序列化失败", e);
        }
        return eventId;
    }

    /**
     * 写入生命周期事件（下达 / 冻结 / 恢复 / 解除冻结原因 / 取消），投递到 workorder-event Topic，Tag 取自 Nacos。
     * payload 统一包含 eventId、orderId（兼容 V2）、workOrderNo、aggregateVersion（工单行当前版本，须在状态更新之后调用）。返回事件号。
     */
    String event(String orderId, String type, Map<String, Object> payload) {
        String tagKey = LIFECYCLE_TAGS.get(type);
        if (tagKey == null) throw new IllegalStateException("未登记投递路由的事件类型：" + type);
        Long version = jdbc.queryForObject("select version from work_order where id = ?", Long.class, orderId);
        var body = new java.util.LinkedHashMap<String, Object>();
        body.put("orderId", orderId);
        body.put("workOrderNo", orderId);
        body.putAll(payload);
        body.put("aggregateVersion", version == null ? 0 : version);
        return routedEvent(orderId, type, mq.topic("workorder-event"), mq.tag(tagKey), body);
    }
}
