package com.smartmanufacturing.inventory;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.List;

/**
 * 预留过期任务：过期只由库存服务判定（契约约定），工单服务只订阅 ReservationExpired，不自行推断。
 * <p>
 * 多实例安全：每批在一个事务里用 {@code SELECT ... FOR UPDATE SKIP LOCKED} 选出到期的 ACTIVE 预留单，
 * 已被其他实例或正在续期/释放/扣减的事务锁住的行被跳过，因此同一张预留单只会被一个实例过期一次、只产生一个 ReservationExpired 事件。
 * 加锁后再按状态与到期时间条件更新，防止选出后被续期的单被误过期。
 */
@Component
public class ReservationExpiryJob {
    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;
    private final ReservationService reservations;
    private final Clock clock;
    private final int batchSize;

    public ReservationExpiryJob(JdbcTemplate jdbc, TransactionTemplate tx, ReservationService reservations, Clock clock,
                                @Value("${inventory.reservation.expiry.batch-size:100}") int batchSize) {
        if (batchSize <= 0) throw new IllegalArgumentException("batch-size 必须为正数");
        this.jdbc = jdbc; this.tx = tx; this.reservations = reservations; this.clock = clock; this.batchSize = batchSize;
    }

    /**
     * 过期全部到期的预留单（分批，每批独立事务，避免长事务）。
     * @return 本次实际过期的数量
     */
    public int expireDue() {
        int total = 0;
        while (true) {
            Instant now = clock.instant();
            int[] r = tx.execute(status -> {
                // LIMIT 为校验过的正整数，直接拼接（各数据库对 LIMIT 绑定参数支持不一致）。
                List<String> ids = jdbc.queryForList("select id from reservation where status = 'ACTIVE' and expires_at < ?"
                    + " order by expires_at limit " + batchSize + " for update skip locked", String.class, Timestamp.from(now));
                int expired = 0;
                for (String id : ids) if (reservations.expireLocked(id, now)) expired++;
                return new int[] {ids.size(), expired};
            });
            total += r[1];
            // 不足一批说明已无可领取的到期单（其余可能被其他实例锁住，交给它们处理）。
            if (r[0] < batchSize) return total;
        }
    }
}
