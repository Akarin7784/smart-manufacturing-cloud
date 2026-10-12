package com.smartmanufacturing.workorder;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;
import java.util.Map;

/**
 * 消息投递目标：绑定 Nacos 共享配置 common.yml 的 manufacturing.mq.*（本地 / 测试 profile 在各自配置文件中提供同名键）。
 * 代码中不写死 Topic / Tag；缺少本服务需要的键时启动失败（{@link #topic} / {@link #tag} 抛出），而不是静默投递到错误目标。
 */
@Validated
@ConfigurationProperties(prefix = "manufacturing.mq")
public record MqProperties(Map<String, String> topics, Map<String, String> tags) {
    /** 按配置键取 Topic，例如 workorder-event。 */
    public String topic(String key) { return require(topics, "topics." + key, key); }
    /** 按配置键取 Tag，例如 workorder-started。 */
    public String tag(String key) { return require(tags, "tags." + key, key); }

    private static String require(Map<String, String> map, String path, String key) {
        String v = map == null ? null : map.get(key);
        if (v == null || v.isBlank()) throw new IllegalStateException("缺少消息配置 manufacturing.mq." + path);
        return v;
    }
}
