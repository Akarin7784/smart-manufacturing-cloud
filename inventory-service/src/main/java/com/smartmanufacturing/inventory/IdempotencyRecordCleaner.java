package com.smartmanufacturing.inventory;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.sql.Statement;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.util.List;

/**
 * 库存幂等记录清理任务（与工单服务 #11 的实现同构）：定时删除 created_at 早于保留期（默认 7 天）的 idempotency_record 记录。
 * <p>
 * 设计要点：
 * <ul>
 *   <li>按 {@code idx_idempotency_record_created_at} 索引按时间顺序选出最旧的记录、按主键分批删除，每批 {@code batch-size} 行、每批独立提交，
 *       避免一次删除大量行造成长事务、大量行锁和主从延迟；单次运行最多 {@code max-batches} 批，剩余留给下次。</li>
 *   <li>多实例安全：未引入 ShedLock 等分布式锁。删除条件只依赖时间截止点，多个实例同时执行时
 *       只会各自删除不同或已不存在的行，结果与单实例一致（幂等），代价仅是少量重复扫描；
 *       实例数增多或清理量变大时再引入 ShedLock（需要额外的锁表迁移）。</li>
 *   <li>只删除幂等记录，不删除业务数据；过期后同一幂等键视为新请求，客户端重试窗口必须远小于保留期。</li>
 * </ul>
 * 可通过 {@code inventory.idempotency.cleanup.enabled=false} 关闭（测试环境默认关闭定时触发）。
 */
@Component
@ConditionalOnProperty(prefix = "inventory.idempotency.cleanup", name = "enabled", havingValue = "true", matchIfMissing = true)
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
                                    @Value("${inventory.idempotency.retention:P7D}") Duration retention,
                                    @Value("${inventory.idempotency.cleanup.batch-size:1000}") int batchSize,
                                    @Value("${inventory.idempotency.cleanup.max-batches:100}") int maxBatches) {
        if (retention.isNegative() || retention.isZero() || batchSize <= 0 || maxBatches <= 0) {
            throw new IllegalArgumentException("幂等记录清理参数必须为正数");
        }
        this.jdbc = jdbc; this.clock = clock; this.retention = retention; this.batchSize = batchSize; this.maxBatches = maxBatches;
    }

    /** 定时入口：默认每天 03:27 执行，错开整点高峰；cron 可通过配置覆盖。 */
    @Scheduled(cron = "${inventory.idempotency.cleanup.cron:0 27 3 * * *}")
    public void scheduledPurge() {
        int deleted = purgeExpired();
        if (deleted > 0) log.info("已清理过期库存幂等记录 {} 条", deleted);
    }

    /**
     * 分批删除过期记录。
     * <p>
     * 每批分两步：先按 created_at 索引升序选出最旧的一批主键，再按主键删除。
     * 不直接使用 {@code DELETE ... LIMIT}：没有确定顺序的 DELETE ... LIMIT 在 statement 格式 binlog 下被 MySQL 判为不安全
     * （主从可能删除不同的行），而 H2 又不支持 DELETE ... ORDER BY；按主键删除对两者都确定且可移植。
     * 删除语句再次带上 created_at 条件，防止选出主键后该键被新请求重新写入时误删新记录。
     *
     * @return 本次删除的总行数
     */
    public int purgeExpired() {
        Timestamp cutoff = Timestamp.from(clock.instant().minus(retention));
        // LIMIT 使用经过校验的正整数拼接：各数据库对 LIMIT 绑定参数的支持不一致。
        String select = "SELECT principal, idempotency_key FROM idempotency_record WHERE created_at < ? ORDER BY created_at LIMIT " + batchSize;
        String delete = "DELETE FROM idempotency_record WHERE principal = ? AND idempotency_key = ? AND created_at < ?";
        int total = 0;
        for (int batch = 0; batch < maxBatches; batch++) {
            List<Object[]> keys = jdbc.query(select, (rs, i) -> new Object[] {rs.getString(1), rs.getString(2), cutoff}, cutoff);
            if (keys.isEmpty()) break;
            // 每批一次批量执行，自动提交模式下每批独立提交，避免长事务。
            for (int count : jdbc.batchUpdate(delete, keys)) {
                // 驱动批量改写时可能返回 SUCCESS_NO_INFO（-2），按删除 1 行计。
                total += count == Statement.SUCCESS_NO_INFO ? 1 : Math.max(count, 0);
            }
            // 不足一批说明已无过期数据，提前结束，避免空转。
            if (keys.size() < batchSize) break;
        }
        return total;
    }
}
