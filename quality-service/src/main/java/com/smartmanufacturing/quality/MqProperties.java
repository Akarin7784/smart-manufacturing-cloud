package com.smartmanufacturing.quality;

import org.springframework.boot.context.properties.ConfigurationProperties;
import java.util.Map;

/**
 * 消息配置，绑定 Nacos 共享配置 common.yml 中的 {@code manufacturing.mq.*}（topic、tag、消费组不在代码中硬编码）。
 * 本服务消费 topics.workorder-quality（tags.defect-*，消费组 consumer-groups.quality-defect），
 * 发布 topics.quality-event（tags.ncr-*）。application.yml 中的默认值仅供无 Nacos 的本地环境使用。
 */
@ConfigurationProperties(prefix = "manufacturing.mq")
public record MqProperties(Map<String, String> topics, Map<String, String> tags, Map<String, String> consumerGroups) {
    /** 本服务发布的 NCR 事件 topic。 */
    public String qualityEventTopic() { return require(topics, "quality-event", "topics"); }
    /** 上游工单服务的不良品事件 topic。 */
    public String workorderQualityTopic() { return require(topics, "workorder-quality", "topics"); }
    /** 不良品事件消费组。 */
    public String defectConsumerGroup() { return require(consumerGroups, "quality-defect", "consumer-groups"); }
    /** DefectReported 的 tag。 */
    public String defectReportedTag() { return require(tags, "defect-reported", "tags"); }
    /** DefectReversed 的 tag。 */
    public String defectReversedTag() { return require(tags, "defect-reversed", "tags"); }

    /** 事件类型 → tag：NcrOpened 对应 tags.ncr-opened，依此类推。 */
    public String tagFor(String eventType) {
        String key = switch (eventType) {
            case "NcrOpened" -> "ncr-opened";
            case "NcrDisposed" -> "ncr-disposed";
            case "NcrClosed" -> "ncr-closed";
            case "NcrVoided" -> "ncr-voided";
            default -> throw new IllegalArgumentException("未知事件类型：" + eventType);
        };
        return require(tags, key, "tags");
    }

    private static String require(Map<String, String> map, String key, String group) {
        String v = map == null ? null : map.get(key);
        if (v == null || v.isBlank()) throw new IllegalStateException("缺少配置 manufacturing.mq." + group + "." + key);
        return v;
    }
}
