package com.smartmanufacturing.quality;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import java.time.Duration;

/**
 * 去重表（consumed_event）与报工锁表（defect_report_lock）的保留期及启动校验（#31）。
 * <p>
 * 保留期必须<b>严格大于</b> Broker 消息保留期 + 冲销暂存期（TTL）：
 * <ul>
 *   <li>Broker 消息保留期内同一事件仍可能被重投（消费位点重置、重投、补发），去重记录必须仍在；</li>
 *   <li>冲销暂存期内原报工可能迟到，报工锁需要保留以串行化对冲。</li>
 * </ul>
 * 配置不满足时构造器抛异常，服务启动失败，避免静默删掉仍可能用到的去重记录。
 * 即使去重记录被清理，ncr.source_report_id 唯一约束仍兜底，迟到的重复报工不会生成第二张 NCR。
 */
@Component
public class DedupRetention {
    private final Duration retention;
    private final Duration brokerRetention;
    private final Duration pendingTtl;

    /**
     * @param retention       两张表保留期，默认 30 天（quality.dedup.retention）
     * @param brokerRetention Broker 消息保留期，默认 72 小时（mes.quality.broker-retention），必须等于 Broker 的 fileReservedTime
     * @param pendingTtl      冲销暂存期（quality.pending-reversal.ttl），默认 24 小时
     */
    public DedupRetention(@Value("${quality.dedup.retention:P30D}") Duration retention,
                          @Value("${mes.quality.broker-retention:PT72H}") Duration brokerRetention,
                          @Value("${quality.pending-reversal.ttl:PT24H}") Duration pendingTtl) {
        if (retention.isNegative() || retention.isZero() || brokerRetention.isNegative() || brokerRetention.isZero())
            throw new IllegalStateException("quality.dedup.retention 与 mes.quality.broker-retention 必须为正");
        Duration floor = brokerRetention.plus(pendingTtl);
        if (retention.compareTo(floor) <= 0)
            throw new IllegalStateException("quality.dedup.retention=" + retention + " 必须大于 mes.quality.broker-retention(" + brokerRetention
                + ") + quality.pending-reversal.ttl(" + pendingTtl + ") = " + floor + "，否则清理后 Broker 仍可能重投已删除去重记录的事件");
        this.retention = retention; this.brokerRetention = brokerRetention; this.pendingTtl = pendingTtl;
    }

    public Duration retention() { return retention; }
    public Duration brokerRetention() { return brokerRetention; }
    public Duration pendingTtl() { return pendingTtl; }
}
