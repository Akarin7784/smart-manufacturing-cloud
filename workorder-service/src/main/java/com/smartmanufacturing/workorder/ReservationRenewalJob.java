package com.smartmanufacturing.workorder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import java.time.Clock;
import java.time.Duration;

/**
 * 物料预留续约作业：每 5 分钟批量续约一次（库存 TTL 15 分钟，留出两次失败的余量）。
 * 只续约、不判定过期；过期由库存服务的 ReservationExpired 事件或续约答复“已失效”驱动。
 * 多实例部署时各实例可能重复续约同一预留，但幂等键按（预留单号, 时间片）生成，库存侧只生效一次。
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

    /** 定时入口；单条失败只记录日志，不影响同批其他预留。返回成功续约数。 */
    @Scheduled(fixedDelayString = "${manufacturing.workorder.reservation-renew-interval:PT5M}",
               initialDelayString = "${manufacturing.workorder.reservation-renew-interval:PT5M}")
    public int renewBatch() {
        long slot = clock.millis() / interval.toMillis();
        int renewed = 0;
        for (var c : service.renewalCandidates(batchSize)) {
            try { if (service.renew(c, slot)) renewed++; }
            catch (RuntimeException e) { LOG.warn("Renewal of reservation {} failed, will retry next round", c.reservationId(), e); }
        }
        return renewed;
    }
}
