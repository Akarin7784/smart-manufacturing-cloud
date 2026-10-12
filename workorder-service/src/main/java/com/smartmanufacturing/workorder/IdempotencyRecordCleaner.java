package com.smartmanufacturing.workorder;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;

/**
 * 工单幂等记录清理任务：定时删除 created_at 早于保留期（默认 7 天）的 work_order_request 记录。
 * <p>
 * 设计要点：
 * <ul>
 *   <li>按 {@code idx_work_order_request_created_at} 索引范围删除，每批 {@code batch-size} 行、每条语句独立提交，
 *       避免一次删除大量行造成长事务、大量行锁和主从延迟；单次运行最多 {@code max-batches} 批，剩余留给下次。</li>
 *   <li>多实例安全：未引入 ShedLock 等分布式锁。删除条件只依赖时间截止点，多个实例同时执行时
 *       只会各自删除不同或已不存在的行，结果与单实例一致（幂等），代价仅是少量重复扫描；
 *       实例数增多或清理量变大时再引入 ShedLock（需要额外的锁表迁移）。</li>
 *   <li>只删除幂等记录，不删除工单；过期后同一幂等键视为新请求，客户端重试窗口必须远小于保留期。</li>
 * </ul>
 * 可通过 {@code workorder.idempotency.cleanup.enabled=false} 关闭（测试环境默认关闭定时触发）。
 */
@Component
@ConditionalOnProperty(prefix = "workorder.idempotency.cleanup", name = "enabled", havingValue = "true", matchIfMissing = true)
public class IdempotencyRecordCleaner {
    private static final Logger log = LoggerFactory.getLogger(IdempotencyRecordCleaner.class);

    private final JdbcTemplate jdbc;
    private final Clock clock;
    private final Duration retention;
    private final int batchSize;
    private final int maxBatches;

    /**
     * @param retention  保留期，默认 7 天，必须为正
     * @param batchSize  每批删除行数上限，必须为正
     * @param maxBatches 单次运行最多批数，防止任务长期占用连接
     */
    public IdempotencyRecordCleaner(JdbcTemplate jdbc, Clock clock,
                                    @Value("${workorder.idempotency.retention:P7D}") Duration retention,
                                    @Value("${workorder.idempotency.cleanup.batch-size:1000}") int batchSize,
                                    @Value("${workorder.idempotency.cleanup.max-batches:100}") int maxBatches) {
        if (retention.isNegative() || retention.isZero() || batchSize <= 0 || maxBatches <= 0) {
            throw new IllegalArgumentException("幂等记录清理参数必须为正数");
        }
        this.jdbc = jdbc; this.clock = clock; this.retention = retention; this.batchSize = batchSize; this.maxBatches = maxBatches;
    }

    /** 定时入口：默认每天 03:17 执行，错开整点高峰；cron 可通过配置覆盖。 */
    @Scheduled(cron = "${workorder.idempotency.cleanup.cron:0 17 3 * * *}")
    public void scheduledPurge() {
        int deleted = purgeExpired();
        if (deleted > 0) log.info("已清理过期工单幂等记录 {} 条", deleted);
    }

    /**
     * 分批删除过期记录。
     *
     * @return 本次删除的总行数
     */
    public int purgeExpired() {
        Timestamp cutoff = Timestamp.from(clock.instant().minus(retention));
        // LIMIT 使用经过校验的整数拼接而非绑定参数：H2（测试）与 MySQL 对 DELETE ... LIMIT ? 的支持不一致。
        String sql = "DELETE FROM work_order_request WHERE created_at < ? LIMIT " + batchSize;
        int total = 0;
        for (int batch = 0; batch < maxBatches; batch++) {
            int deleted = jdbc.update(sql, cutoff);
            total += deleted;
            // 不足一批说明已无过期数据，提前结束，避免空转。
            if (deleted < batchSize) break;
        }
        return total;
    }
}
