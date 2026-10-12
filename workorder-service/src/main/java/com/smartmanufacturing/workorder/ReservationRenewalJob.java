package com.smartmanufacturing.workorder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;

/**
 * 物料预留续约作业：每 5 分钟一轮（库存 TTL 15 分钟，留出两轮失败的余量）。每轮按批（默认 200）循环取批直至取空，
 * 覆盖全部活动预留；单轮耗时超过间隔的一半时告警积压（继续拖延会逼近 TTL）。
 * 只续约、不判定过期；过期由库存的 ReservationExpired 事件或续约答复“已失效”驱动。
 * 多实例部署时可能重复续约同一预留，但幂等键按（预留单号, 时间片）生成，库存侧只生效一次。
 */
@Component
public class ReservationRenewalJob {
    private static final Logger LOG = LoggerFactory.getLogger(ReservationRenewalJob.class);
    private final WorkOrderReleaseService service;
    private final Clock clock;
    private final Duration interval;
    private final int batchSize;

    ReservationRenewalJob(WorkOrderReleaseService service, Clock clock,
                          @Value("${manufacturing.workorder.reservation-renew-interval:PT5M}") Duration interval,
                          @Value("${manufacturing.workorder.reservation-renew-batch-size:200}") int batchSize) {
        this.service = service; this.clock = clock; this.interval = interval; this.batchSize = batchSize;
    }

    /** 定时入口；单条失败只记录日志，不影响其他预留（失败的会在下一轮重试）。返回本轮成功续约数。 */
    @Scheduled(fixedDelayString = "${manufacturing.workorder.reservation-renew-interval:PT5M}",
               initialDelayString = "${manufacturing.workorder.reservation-renew-interval:PT5M}")
    public int renewBatch() {
        Instant start = clock.instant();
        long slot = start.toEpochMilli() / interval.toMillis();
        int renewed = 0, failed = 0, batches = 0;
        String cursor = "";
        while (true) {
            var batch = service.renewalCandidates(start, cursor, batchSize);
            if (batch.isEmpty()) break;
            batches++;
            for (var c : batch) {
                try { if (service.renew(c, slot)) renewed++; else failed++; }
                catch (RuntimeException e) { failed++; LOG.warn("Renewal of reservation {} failed, will retry next round", c.reservationId(), e); }
            }
            cursor = batch.get(batch.size() - 1).reservationId();
        }
        Duration took = Duration.between(start, clock.instant());
        if (took.compareTo(interval.dividedBy(2)) > 0)
            LOG.warn("Reservation renewal backlog: {} batches took {} (interval {}), renewed={}, failed={}", batches, took, interval, renewed, failed);
        return renewed;
    }
}
