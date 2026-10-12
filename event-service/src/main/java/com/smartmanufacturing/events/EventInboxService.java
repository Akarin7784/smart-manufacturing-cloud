package com.smartmanufacturing.events;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.smartmanufacturing.api.WorkOrderCreatedEvent;
import jakarta.validation.Validator;
import java.security.MessageDigest;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.HexFormat;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/** 将去重记录与业务投影放在同一事务内，确认成功后消息才可被 ACK。 */
@Service
public class EventInboxService {
    private final JdbcTemplate jdbc;
    private final TransactionTemplate transactions;
    private final ObjectMapper mapper;
    private final Validator validator;
    public EventInboxService(JdbcTemplate jdbc, TransactionTemplate transactions, ObjectMapper mapper, Validator validator) {
        this.jdbc = jdbc; this.transactions = transactions; this.mapper = mapper; this.validator = validator;
    }

    /** 解析并校验版本与内容；重复 ID 必须对应相同内容，不能静默吞掉冲突事件。 */
    public void accept(byte[] payload) throws Exception {
        WorkOrderCreatedEvent event = mapper.readValue(payload, WorkOrderCreatedEvent.class);
        if (!validator.validate(event).isEmpty()) throw new IllegalArgumentException("工单事件字段或版本不合法");
        String hash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(mapper.writeValueAsBytes(event)));
        Instant received = Instant.now();
        try {
            transactions.executeWithoutResult(status -> {
                jdbc.update("insert into processed_event (event_id, payload_hash, processed_at) values (?, ?, ?)",
                    event.eventId(), hash, Timestamp.from(received));
                jdbc.update("insert into work_order_event (event_id, order_id, device_id, product, quantity, occurred_at, received_at) values (?, ?, ?, ?, ?, ?, ?)",
                    event.eventId(), event.orderId(), event.deviceId(), event.product(), event.quantity(),
                    Timestamp.from(event.occurredAt()), Timestamp.from(received));
            });
        } catch (DuplicateKeyException e) {
            // 在回滚后的新查询中确认是同内容重投，避免把其他唯一约束冲突当作成功。
            var hashes = jdbc.query("select payload_hash from processed_event where event_id = ?",
                (rs, row) -> rs.getString(1), event.eventId());
            if (hashes.size() != 1 || !hash.equals(hashes.getFirst())) throw e;
        }
    }
}
