package com.smartmanufacturing.quality;

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
 * 暂存冲销过期任务：把 WAITING 且收到时间早于（当前时间 − TTL）的暂存标为 EXPIRED，计数
 * mes_quality_pending_reversal_expired_total（告警 QualityReversalOrphan），由人工按 reversedReportId 去工单侧核对。
 * <ul>
 *   <li>TTL 由 {@code quality.pending-reversal.ttl} 配置（默认 PT24H）；时间取注入的 {@link Clock}，测试可推进时钟验证。</li>
 *   <li>每批用 {@code FOR UPDATE SKIP LOCKED} 领取，多实例并发执行互不重复计数；更新时再次限定 status=WAITING，
 *       与正在对冲的消费事务（持有同一暂存行锁）互斥，已 MATCHED 的不会被改成 EXPIRED。</li>
 *   <li>过期后原报工才到达：按正常报工建 OPEN 的 NCR，暂存保持 EXPIRED（见 {@link DefectEventHandler}）。</li>
 * </ul>
 */
@Component
public class PendingReversalExpiryJob {
    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;
    private final QualityMetrics metrics;
    private final Clock clock;
    private final Duration ttl;
    private final int batchSize;

    public PendingReversalExpiryJob(JdbcTemplate jdbc, TransactionTemplate tx, QualityMetrics metrics, Clock clock,
                                    @Value("${quality.pending-reversal.ttl:PT24H}") Duration ttl,
                                    @Value("${quality.pending-reversal.expiry.batch-size:100}") int batchSize) {
        if (ttl.isNegative() || ttl.isZero() || batchSize <= 0) throw new IllegalArgumentException("暂存冲销 TTL 与批大小必须为正");
        this.jdbc = jdbc; this.tx = tx; this.metrics = metrics; this.clock = clock; this.ttl = ttl; this.batchSize = batchSize;
    }

    /** 当前配置的 TTL。 */
    public Duration ttl() { return ttl; }

    /**
     * 过期全部到期的暂存（分批提交）。
     * @return 本次标为 EXPIRED 的条数
     */
    public int expireDue() {
        int total = 0;
        while (true) {
            Integer n = tx.execute(status -> {
                Instant now = clock.instant();
                Timestamp cutoff = Timestamp.from(now.minus(ttl));
                List<String> ids = jdbc.queryForList("select reversed_report_id from pending_reversal where status = 'WAITING' and received_at < ?"
                    + " order by received_at limit " + batchSize + " for update skip locked", String.class, cutoff);
                int expired = 0;
                for (String id : ids) {
                    expired += jdbc.update("update pending_reversal set status = 'EXPIRED', updated_at = ? where reversed_report_id = ? and status = 'WAITING'",
                        Timestamp.from(now), id);
                }
                metrics.pendingExpired(expired);
                return ids.size() < batchSize ? -expired - 1 : expired;
            });
            // 返回负数表示最后一批（编码为 -expired-1），避免多一次空查询。
            if (n == null) break;
            if (n < 0) { total += -n - 1; break; }
            total += n;
        }
        return total;
    }
}
