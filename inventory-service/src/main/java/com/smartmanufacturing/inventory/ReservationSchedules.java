package com.smartmanufacturing.inventory;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 预留相关的定时触发器：过期任务与 outbox 发布任务。业务逻辑在 {@link ReservationExpiryJob}、{@link OutboxPublisher} 中，
 * 本类只负责调度，测试中通过 {@code inventory.jobs.enabled=false} 关闭，由用例直接调用任务方法，避免后台线程干扰断言。
 */
@Component
@ConditionalOnProperty(prefix = "inventory.jobs", name = "enabled", havingValue = "true", matchIfMissing = true)
public class ReservationSchedules {
    private static final Logger log = LoggerFactory.getLogger(ReservationSchedules.class);
    private final ReservationExpiryJob expiry;
    private final OutboxPublisher publisher;

    public ReservationSchedules(ReservationExpiryJob expiry, OutboxPublisher publisher) { this.expiry = expiry; this.publisher = publisher; }

    /** 每 30 秒检查一次到期预留（TTL 15 分钟，调用方每 5 分钟续期，30 秒粒度足够）。 */
    @Scheduled(fixedDelayString = "${inventory.reservation.expiry.interval:PT30S}")
    public void expire() {
        int n = expiry.expireDue();
        if (n > 0) log.info("已过期预留单 {} 张", n);
    }

    /** 每秒投递一次 outbox 事件，单批上限见 inventory.outbox.batch-size。 */
    @Scheduled(fixedDelayString = "${inventory.outbox.interval:PT1S}")
    public void publish() { publisher.publishBatch(); }
}
