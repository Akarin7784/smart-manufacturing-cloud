package com.smartmanufacturing.workorder.messaging;

import com.smartmanufacturing.messaging.RocketMqProperties;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/** 持久化发件箱状态与租约，通过条件更新防止多个实例同时拥有同一事件。 */
@Service
public class OutboxStore {
    private final JdbcTemplate jdbc;
    private final RocketMqProperties properties;
    public OutboxStore(JdbcTemplate jdbc, RocketMqProperties properties) { this.jdbc = jdbc; this.properties = properties; }

    /** 查询可发送或租约已过期的事件；候选列表并不代表已经取得所有权。 */
    public List<String> dueIds(Instant now) {
        return jdbc.query("select event_id from outbox_event where (status = 'PENDING' and available_at <= ?) "
            + "or (status = 'PROCESSING' and lease_until <= ?) order by created_at, event_id limit ?",
            (rs, row) -> rs.getString(1), Timestamp.from(now), Timestamp.from(now), properties.getBatchSize());
    }

    /** 每次发送前单独抢占事件；崩溃后的 PROCESSING 事件可在租约到期后重新抢占。 */
    public Optional<Claim> claim(String eventId, Instant now) {
        String token = UUID.randomUUID().toString();
        int changed = jdbc.update("update outbox_event set status = 'PROCESSING', claim_token = ?, lease_until = ?, attempts = attempts + 1 "
            + "where event_id = ? and ((status = 'PENDING' and available_at <= ?) or (status = 'PROCESSING' and lease_until <= ?))",
            token, Timestamp.from(now.plusSeconds(properties.getLeaseSeconds())), eventId, Timestamp.from(now), Timestamp.from(now));
        if (changed != 1) return Optional.empty();
        return jdbc.query("select event_id, payload, attempts from outbox_event where event_id = ? and claim_token = ?",
            (rs, row) -> new Claim(rs.getString(1), rs.getString(2), rs.getInt(3), token), eventId, token).stream().findFirst();
    }

    /** 只有仍持有租约的发送者才能确认成功，避免旧实例覆盖新实例的状态。 */
    public void sent(Claim claim, Instant now) {
        jdbc.update("update outbox_event set status = 'SENT', sent_at = ?, lease_until = null, claim_token = null, last_error = null "
            + "where event_id = ? and status = 'PROCESSING' and claim_token = ?", Timestamp.from(now), claim.eventId(), claim.token());
    }

    /** 失败使用有限指数退避，超过自动重试次数后进入 FAILED 等待管理员处理。 */
    public void failed(Claim claim, Instant now, Exception error) {
        long delay = Math.min(300L, (long) properties.getRetryBaseSeconds() * (1L << Math.min(claim.attempts() - 1, 16)));
        String message = error.getClass().getSimpleName() + ": " + Objects.toString(error.getMessage(), "send failed");
        if (message.length() > 512) message = message.substring(0, 512);
        jdbc.update("update outbox_event set status = ?, available_at = ?, lease_until = null, claim_token = null, last_error = ? "
            + "where event_id = ? and status = 'PROCESSING' and claim_token = ?",
            claim.attempts() >= properties.getMaxAttempts() ? "FAILED" : "PENDING", Timestamp.from(now.plusSeconds(delay)),
            message, claim.eventId(), claim.token());
    }

    /** 状态汇总供管理员监控，不返回消息负载或内部连接错误。 */
    public Map<String, Long> counts() {
        Map<String, Long> result = new LinkedHashMap<>();
        for (String status : List.of("PENDING", "PROCESSING", "SENT", "FAILED")) result.put(status, 0L);
        jdbc.query("select status, count(*) as total from outbox_event group by status", rs -> {
            result.put(rs.getString("status"), rs.getLong("total"));
        });
        return result;
    }

    /** 管理员仅能重试 FAILED 事件，事件 ID 与原始负载保持不变。 */
    public boolean retry(String eventId) {
        return jdbc.update("update outbox_event set status = 'PENDING', attempts = 0, available_at = ?, last_error = null "
            + "where event_id = ? and status = 'FAILED'", Timestamp.from(Instant.now()), eventId) == 1;
    }

    /** 保存数据库租约信息；确认和失败更新都必须带上当前令牌。 */
    public record Claim(String eventId, String payload, int attempts, String token) {}
}
