package com.smartmanufacturing.quality;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;
import java.sql.Timestamp;
import java.time.Clock;
import java.util.List;

/**
 * 去重表与报工锁表清理（#31）：按创建时间删除早于（当前时间 − 保留期）的行。
 * <ul>
 *   <li>每批最多 batch-size（默认 1000）行、一批一个事务：先按时间索引 {@code FOR UPDATE SKIP LOCKED} 选出主键，再按主键删除，
 *       不长时间持锁；正被消费事务锁住的报工锁行直接跳过，留到下次。</li>
 *   <li>单次运行最多 max-batches 批，剩余留给下次；删除行数计入 mes_quality_cleanup_deleted_total{table}（提交后计数）。</li>
 *   <li>定时触发在 {@link QualitySchedules}，受 quality.jobs.enabled 控制；时间取注入的 {@link Clock}。</li>
 * </ul>
 */
@Component
public class DedupTableCleaner {
    /** 表名 → （主键列, 时间列）；只允许这两张表，SQL 拼接的标识符均来自此处常量。 */
    private record Target(String table, String key, String time) {}
    private static final List<Target> TARGETS = List.of(
        new Target("consumed_event", "event_id", "consumed_at"),
        new Target("defect_report_lock", "report_id", "created_at"));

    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;
    private final QualityMetrics metrics;
    private final DedupRetention retention;
    private final Clock clock;
    private final int batchSize;
    private final int maxBatches;

    public DedupTableCleaner(JdbcTemplate jdbc, TransactionTemplate tx, QualityMetrics metrics, DedupRetention retention, Clock clock,
                             @Value("${quality.dedup.cleanup.batch-size:1000}") int batchSize,
                             @Value("${quality.dedup.cleanup.max-batches:100}") int maxBatches) {
        if (batchSize <= 0 || maxBatches <= 0) throw new IllegalArgumentException("清理批大小与批数必须为正");
        this.jdbc = jdbc; this.tx = tx; this.metrics = metrics; this.retention = retention; this.clock = clock;
        this.batchSize = batchSize; this.maxBatches = maxBatches;
    }

    /**
     * 清理两张表的过期行。
     * @return 本次删除的总行数
     */
    public int purgeExpired() {
        Timestamp cutoff = Timestamp.from(clock.instant().minus(retention.retention()));
        int total = 0;
        for (Target t : TARGETS) total += purge(t, cutoff);
        return total;
    }

    private int purge(Target t, Timestamp cutoff) {
        String select = "select " + t.key() + " from " + t.table() + " where " + t.time() + " < ? order by " + t.time()
            + " limit " + batchSize + " for update skip locked";
        // 删除时再次带上时间条件，防止主键被选出后又被新数据重新写入（如同一报工锁行被重新插入）。
        String delete = "delete from " + t.table() + " where " + t.key() + " = ? and " + t.time() + " < ?";
        int total = 0;
        for (int batch = 0; batch < maxBatches; batch++) {
            int[] result = tx.execute(status -> {
                List<String> keys = jdbc.queryForList(select, String.class, cutoff);
                int deleted = 0;
                for (String k : keys) deleted += jdbc.update(delete, k, cutoff);
                return new int[] {keys.size(), deleted};
            });
            if (result == null) break;
            total += result[1];
            metrics.cleanupDeleted(t.table(), result[1]);
            if (result[0] < batchSize) break;
        }
        return total;
    }
}
