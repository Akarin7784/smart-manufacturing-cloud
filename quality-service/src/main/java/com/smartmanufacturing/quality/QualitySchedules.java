package com.smartmanufacturing.quality;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 质检定时触发器：暂存冲销过期与 outbox 发布。业务逻辑在 {@link PendingReversalExpiryJob}、{@link OutboxPublisher} 中，
 * 本类只负责调度，测试中通过 {@code quality.jobs.enabled=false} 关闭，由用例直接调用任务方法。
 */
@Component
@ConditionalOnProperty(prefix = "quality.jobs", name = "enabled", havingValue = "true", matchIfMissing = true)
public class QualitySchedules {
    private static final Logger log = LoggerFactory.getLogger(QualitySchedules.class);
    private final PendingReversalExpiryJob expiry;
    private final OutboxPublisher publisher;

    public QualitySchedules(PendingReversalExpiryJob expiry, OutboxPublisher publisher) { this.expiry = expiry; this.publisher = publisher; }

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
