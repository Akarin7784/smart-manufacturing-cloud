package com.smartmanufacturing.inventory;

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
 * outbox 写入：事件与业务数据在<b>同一本地事务</b>内落库（MANDATORY 传播，调用方没有事务时直接报错），
 * 业务回滚则事件一并回滚，不会出现“发了事件但业务没生效”。投递由 {@link OutboxPublisher} 异步完成。
 * <p>
 * eventId 在写入时生成并固定，补发不变，消费端据此幂等。
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
     * 追加一条预留事件。
     * @param eventType     ReservationCreated / ReservationReleased / ReservationConsumed / ReservationExpired
     * @param reservationId 预留单 ID（同时作为 RocketMQ 消息键）
     * @param common        workOrderNo、status 等公共字段之外的扩展字段
     * @return 生成的 eventId
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public String append(String eventType, String reservationId, String workOrderNo, String status, Map<String, Object> common) {
        String eventId = UUID.randomUUID().toString();
        // 截断到毫秒（向下取整）：next_attempt_at / created_at 为 TIMESTAMP(3)，H2 与 MySQL 写入时会把多余小数位四舍五入，
        // 可能存成比写入时刻更晚的值；同一毫秒内发布时 next_attempt_at <= now 不成立，事件被误判为未到期（#46）。
        Instant now = clock.instant().truncatedTo(java.time.temporal.ChronoUnit.MILLIS);
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("eventId", eventId);
        payload.put("eventType", eventType);
        payload.put("reservationId", reservationId);
        payload.put("workOrderNo", workOrderNo);
        payload.put("status", status);
        // aggregateVersion：预留单行的乐观锁版本（同一事务内读取，已包含本次状态变更的 +1），按预留单单调递增；
        // 消费端以 reservationId 为键，丢弃 aggregateVersion 不大于本地已处理版本的事件，从而抵御乱序与重复投递。
        payload.put("aggregateVersion", jdbc.queryForObject("select version from reservation where id = ?", Long.class, reservationId));
        payload.put("occurredAt", now);
        payload.putAll(common);
        try {
            jdbc.update("insert into outbox_event (event_id, event_type, aggregate_id, payload, status, attempts, next_attempt_at, created_at)"
                + " values (?, ?, ?, ?, 'NEW', 0, ?, ?)", eventId, eventType, reservationId, mapper.writeValueAsString(payload),
                Timestamp.from(now), Timestamp.from(now));
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("事件序列化失败", e);
        }
        return eventId;
    }
}
