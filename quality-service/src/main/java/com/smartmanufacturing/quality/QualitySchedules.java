package com.smartmanufacturing.quality;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 质检定时触发器：暂存冲销过期、outbox 发布、库存回写、去重表 / 报工锁表 / 终态回写任务清理。业务逻辑在 {@link PendingReversalExpiryJob}、{@link OutboxPublisher} 中，
 * 本类只负责调度，测试中通过 {@code quality.jobs.enabled=false} 关闭，由用例直接调用任务方法。
 */
@Component
@ConditionalOnProperty(prefix = "quality.jobs", name = "enabled", havingValue = "true", matchIfMissing = true)
public class QualitySchedules {
    private static final Logger log = LoggerFactory.getLogger(QualitySchedules.class);
    private final PendingReversalExpiryJob expiry;
    private final OutboxPublisher publisher;
    private final DedupTableCleaner cleaner;
    private final WritebackWorker writeback;
    private final WritebackService writebacks;

    public QualitySchedules(PendingReversalExpiryJob expiry, OutboxPublisher publisher, DedupTableCleaner cleaner,
                            WritebackWorker writeback, WritebackService writebacks) {
        this.expiry = expiry; this.publisher = publisher; this.cleaner = cleaner; this.writeback = writeback; this.writebacks = writebacks;
    }

    /** 每天 03:47 清理过期的去重记录与报工锁（错开幂等记录清理的 03:37），并清理超过保留期（默认 P30D）的终态回写任务。 */
    @Scheduled(cron = "${quality.dedup.cleanup.cron:0 47 3 * * *}")
    public void purgeDedup() {
        int n = cleaner.purgeExpired();
        if (n > 0) log.info("已清理过期去重记录与报工锁 {} 行", n);
        int w = writebacks.purgeTerminal(1000, 100);
        if (w > 0) log.info("已清理终态库存回写任务 {} 行", w);
    }

    /** 每 2s 执行一批到期的库存回写任务（mes.quality.writeback.interval）。 */
    @Scheduled(fixedDelayString = "${mes.quality.writeback.interval:PT2S}")
    public void writeback() { writeback.runBatch(); }

    /** 每分钟检查一次超时的暂存冲销（TTL 默认 24 小时，分钟级粒度足够）。 */
    @Scheduled(fixedDelayString = "${quality.pending-reversal.expiry.interval:PT1M}")
    public void expire() {
        int n = expiry.expireDue();
        if (n > 0) log.warn("暂存冲销超时未对冲 {} 条，已标记 EXPIRED，需人工核对", n);
    }

    /** 每秒投递一次 outbox 事件，单批上限见 quality.outbox.batch-size。 */
    @Scheduled(fixedDelayString = "${quality.outbox.interval:PT1S}")
    public void publish() { publisher.publishBatch(); }
}
