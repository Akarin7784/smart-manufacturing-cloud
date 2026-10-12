package com.smartmanufacturing.inventory;

import org.springframework.boot.context.properties.ConfigurationProperties;
import java.util.Map;

/**
 * 消息配置，绑定 Nacos 共享配置 common.yml 中的 {@code manufacturing.mq.*}（topic、tag 不在代码中硬编码）。
 * 本服务只使用 topics.inventory-reservation 与 tags.reservation-*；application.yml 中的默认值与 infra/nacos/common.yml 保持一致，
 * 仅供无 Nacos 的本地环境使用。
 */
@ConfigurationProperties(prefix = "manufacturing.mq")
public record MqProperties(Map<String, String> topics, Map<String, String> tags) {
    /** 预留事件 topic。 */
    public String reservationTopic() { return require(topics, "inventory-reservation", "topics"); }

    /** 事件类型 → tag：ReservationCreated 对应 tags.reservation-created，依此类推。 */
    public String tagFor(String eventType) {
        String key = switch (eventType) {
            case "ReservationCreated" -> "reservation-created";
            case "ReservationReleased" -> "reservation-released";
            case "ReservationConsumed" -> "reservation-consumed";
            case "ReservationExpired" -> "reservation-expired";
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
