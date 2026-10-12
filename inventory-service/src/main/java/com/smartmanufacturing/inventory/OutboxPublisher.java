package com.smartmanufacturing.inventory;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;

/**
 * outbox 发布任务：把 NEW 状态的事件投递到 RocketMQ，投递语义为<b>至少一次</b>。
 * <ul>
 *   <li>多实例安全：每批用 {@code FOR UPDATE SKIP LOCKED} 领取事件，同一事件同一时刻只被一个实例发送。</li>
 *   <li>发送成功后标记 SENT；失败则该条 attempts+1 并按指数退避（上限 5 分钟）设置下次尝试时间，event_id 不变。</li>
 *   <li><b>首次失败即结束本批</b>：同批剩余事件不发送、attempts 不变，留待下一轮。Broker 不可用时每条都会等满发送超时，
 *       继续发送只会让领取事务长时间持有行锁和连接，且几乎必然继续失败。</li>
 *   <li>Broker 已确认但标记 SENT 前进程崩溃时，事件会被再次发送——这正是消费端必须按 eventId 幂等的原因。</li>
 *   <li>发送在领取事务内同步进行（单条超时 3 秒、单批上限 50 条），以行锁防止其他实例重复领取；代价是批次期间占用一个连接。</li>
 *   <li>按 id 顺序领取，但不保证同一预留单事件的到达顺序（契约已说明）。</li>
 * </ul>
 */
@Component
public class OutboxPublisher {
    private static final Logger log = LoggerFactory.getLogger(OutboxPublisher.class);
    private static final Duration MAX_BACKOFF = Duration.ofMinutes(5);

    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;
    private final EventSender sender;
    private final MqProperties mq;
    private final Clock clock;
    private final int batchSize;

    public OutboxPublisher(JdbcTemplate jdbc, TransactionTemplate tx, EventSender sender, MqProperties mq, Clock clock,
                           @Value("${inventory.outbox.batch-size:50}") int batchSize) {
        if (batchSize <= 0) throw new IllegalArgumentException("batch-size 必须为正数");
        this.jdbc = jdbc; this.tx = tx; this.sender = sender; this.mq = mq; this.clock = clock; this.batchSize = batchSize;
    }

    private record Pending(long id, String eventId, String eventType, String aggregateId, String payload, int attempts) {}

    /**
     * 发布一批到期的待发事件。
     * @return 本批发送成功的数量
     */
    public int publishBatch() {
        Integer sent = tx.execute(status -> {
            Instant now = clock.instant();
            List<Pending> batch = jdbc.query("select id, event_id, event_type, aggregate_id, payload, attempts from outbox_event"
                    + " where status = 'NEW' and next_attempt_at <= ? order by id limit " + batchSize + " for update skip locked",
                (rs, i) -> new Pending(rs.getLong(1), rs.getString(2), rs.getString(3), rs.getString(4), rs.getString(5), rs.getInt(6)),
                Timestamp.from(now));
            int ok = 0;
            for (Pending p : batch) {
                try {
                    sender.send(mq.tagFor(p.eventType()), p.aggregateId(), p.eventId(), p.payload());
                    jdbc.update("update outbox_event set status = 'SENT', sent_at = ?, attempts = attempts + 1, last_error = null where id = ?",
                        Timestamp.from(clock.instant()), p.id());
                    ok++;
                } catch (Exception e) {
                    // 只给失败的这一条退避并计数，eventId 保持不变；随后结束本批（见类注释），剩余事件不动。
                    Duration backoff = backoff(p.attempts() + 1);
                    String error = String.valueOf(e.getMessage());
                    jdbc.update("update outbox_event set attempts = attempts + 1, next_attempt_at = ?, last_error = ? where id = ?",
                        Timestamp.from(clock.instant().plus(backoff)), error.length() > 500 ? error.substring(0, 500) : error, p.id());
                    log.warn("事件投递失败，{} 后重试，本批剩余事件留待下一轮：eventId={}, type={}", backoff, p.eventId(), p.eventType(), e);
                    break;
                }
            }
            return ok;
        });
        return sent == null ? 0 : sent;
    }

    /** 指数退避：1s、2s、4s……上限 5 分钟。 */
    static Duration backoff(int attempts) {
        long seconds = 1L << Math.min(Math.max(attempts - 1, 0), 20);
        Duration d = Duration.ofSeconds(seconds);
        return d.compareTo(MAX_BACKOFF) > 0 ? MAX_BACKOFF : d;
    }
}
