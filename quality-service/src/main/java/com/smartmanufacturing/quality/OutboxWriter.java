package com.smartmanufacturing.quality;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * outbox 写入：NCR 事件与业务数据在<b>同一本地事务</b>内落库（MANDATORY 传播，调用方没有事务时直接报错），
 * 业务回滚则事件一并回滚。投递由 {@link OutboxPublisher} 异步完成，eventId 在写入时生成并固定，补发不变。
 */
@Component
public class OutboxWriter {
    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;
    private final Clock clock;

    public OutboxWriter(JdbcTemplate jdbc, ObjectMapper mapper, Clock clock) {
        this.jdbc = jdbc; this.mapper = mapper; this.clock = clock;
    }

    /**
     * 追加一条 NCR 事件。公共字段（NcrEventBase）从 NCR 行读取（同一事务内，已包含本次变更），保证与库中状态一致。
     * @param eventType NcrOpened / NcrDisposed / NcrClosed / NcrVoided
     * @param ncrNo     NCR 编号（同时作为 RocketMQ 消息键）
     * @param extra     各事件类型的扩展字段
     * @return 生成的 eventId
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public String append(String eventType, String ncrNo, Map<String, Object> extra) {
        String eventId = UUID.randomUUID().toString();
        // 截断到毫秒（向下取整）：next_attempt_at / created_at 为 TIMESTAMP(3)，H2 与 MySQL 写入时会把多余小数位四舍五入，
        // 可能存成比写入时刻更晚的值；同一毫秒内发布时 next_attempt_at <= now 不成立，事件被误判为未到期（#46）。
        Instant now = clock.instant().truncatedTo(java.time.temporal.ChronoUnit.MILLIS);
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("eventId", eventId);
        payload.put("eventType", eventType);
        jdbc.query("select ncr_no, source_report_id, work_order_no, status, version from ncr where ncr_no = ?", rs -> {
            payload.put("ncrNo", rs.getString(1));
            payload.put("sourceReportId", rs.getString(2));
            payload.put("workOrderNo", rs.getString(3));
            payload.put("status", rs.getString(4));
            // aggregateVersion：NCR 行的版本（已包含本次变更的 +1），按 NCR 单调递增；消费端丢弃不大于本地已处理版本的事件。
            payload.put("aggregateVersion", rs.getLong(5));
        }, ncrNo);
        if (!payload.containsKey("ncrNo")) throw new IllegalStateException("写事件时找不到 NCR " + ncrNo);
        payload.put("occurredAt", now);
        payload.putAll(extra);
        try {
            jdbc.update("insert into outbox_event (event_id, event_type, aggregate_id, payload, status, attempts, next_attempt_at, created_at)"
                + " values (?, ?, ?, ?, 'NEW', 0, ?, ?)", eventId, eventType, ncrNo, mapper.writeValueAsString(payload),
                Timestamp.from(now), Timestamp.from(now));
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("事件序列化失败", e);
        }
        return eventId;
    }
}
