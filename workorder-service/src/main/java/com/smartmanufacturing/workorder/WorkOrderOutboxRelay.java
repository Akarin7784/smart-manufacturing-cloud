package com.smartmanufacturing.workorder;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;

/**
 * 工单服务 outbox 中继（沿用 #18 库存服务的模式），投递语义为<b>至少一次</b>：
 * <ul>
 *   <li>每批用 {@code SELECT … FOR UPDATE SKIP LOCKED} 领取 status = NEW 且已到期的行，多实例不会重复领取同一行。</li>
 *   <li>发送成功：status = SENT，写 published_at。失败：仅该行 attempts + 1，并按指数退避（1s、2s、4s…上限 5 分钟）写 next_attempt_at。</li>
 *   <li><b>首次失败即结束本批</b>：剩余行不尝试、attempts 不变。Broker 不可用时每条都会等满发送超时，继续发送只会延长持锁时间。</li>
 *   <li>重发时 event_id 不变；Broker 已确认但标记前崩溃会导致重复投递，消费端必须按 eventId 幂等。</li>
 *   <li>DISCARDED 行（V3 回填中无法映射的遗留事件）永不领取。</li>
 * </ul>
 * 发送在领取事务内同步进行，单条超时短（默认 3 秒）、单批上限默认 50 条。
 */
@Component
public class WorkOrderOutboxRelay {
    private static final Logger LOG = LoggerFactory.getLogger(WorkOrderOutboxRelay.class);
    private static final Duration MAX_BACKOFF = Duration.ofMinutes(5);

    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;
    private final EventSender sender;
    private final Clock clock;
    private final int batchSize;

    public WorkOrderOutboxRelay(JdbcTemplate jdbc, TransactionTemplate tx, EventSender sender, Clock clock,
                                @Value("${workorder.outbox.batch-size:50}") int batchSize) {
        if (batchSize <= 0) throw new IllegalArgumentException("workorder.outbox.batch-size 必须为正数");
        this.jdbc = jdbc; this.tx = tx; this.sender = sender; this.clock = clock; this.batchSize = batchSize;
    }

    private record Pending(String eventId, String aggregateId, String eventType, String topic, String tag, String payload, int attempts) {}

    /** 发布一批到期事件，返回本批发送成功的条数。 */
    public int publishBatch() {
        Integer sent = tx.execute(status -> {
            Instant now = clock.instant();
            List<Pending> batch = jdbc.query("select event_id, aggregate_id, event_type, topic, tag, payload, attempts from outbox_event"
                    + " where status = 'NEW' and topic is not null and (next_attempt_at is null or next_attempt_at <= ?)"
                    + " order by occurred_at, event_id limit " + batchSize + " for update skip locked",
                (rs, i) -> new Pending(rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4), rs.getString(5), rs.getString(6), rs.getInt(7)),
                DbTime.ts(now));
            int ok = 0;
            for (Pending p : batch) {
                try {
                    sender.send(p.topic(), p.tag(), p.aggregateId(), p.eventId(), p.payload());
                    jdbc.update("update outbox_event set status = 'SENT', published_at = ?, attempts = attempts + 1, last_error = null where event_id = ?",
                        DbTime.ts(clock.instant()), p.eventId());
                    ok++;
                } catch (Exception e) {
                    // 只给失败的这一条计数并退避，eventId 不变；随后结束本批，剩余行留待下一轮
                    Duration backoff = backoff(p.attempts() + 1);
                    String error = String.valueOf(e.getMessage());
                    jdbc.update("update outbox_event set attempts = attempts + 1, next_attempt_at = ?, last_error = ? where event_id = ?",
                        DbTime.ts(clock.instant().plus(backoff)), error.length() > 500 ? error.substring(0, 500) : error, p.eventId());
                    LOG.warn("事件投递失败，{} 后重试，本批剩余 {} 条留待下一轮：eventId={} type={} topic={}",
                        backoff, batch.size() - ok - 1, p.eventId(), p.eventType(), p.topic(), e);
                    break;
                }
            }
            if (ok > 0) LOG.debug("outbox 中继本批投递成功 {} 条", ok);
            return ok;
        });
        return sent == null ? 0 : sent;
    }

    /** 指数退避：第 n 次失败后等待 2^(n-1) 秒，上限 5 分钟。 */
    static Duration backoff(int attempts) {
        long seconds = 1L << Math.min(Math.max(attempts - 1, 0), 20);
        Duration d = Duration.ofSeconds(seconds);
        return d.compareTo(MAX_BACKOFF) > 0 ? MAX_BACKOFF : d;
    }

    /** 定时调度；测试环境通过 workorder.outbox.relay.enabled=false 关闭，由用例直接调用 publishBatch。 */
    @Component
    @ConditionalOnProperty(name = "workorder.outbox.relay.enabled", havingValue = "true", matchIfMissing = true)
    static class Schedule {
        private final WorkOrderOutboxRelay relay;
        Schedule(WorkOrderOutboxRelay relay) { this.relay = relay; }

        @Scheduled(fixedDelayString = "${workorder.outbox.interval:PT1S}", initialDelayString = "${workorder.outbox.interval:PT1S}")
        void run() {
            try { relay.publishBatch(); } catch (RuntimeException e) { LOG.error("outbox 中继调度异常，下一轮重试", e); }
        }
    }
}
