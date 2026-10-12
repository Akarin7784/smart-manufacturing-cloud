package com.smartmanufacturing.quality;

import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.context.PropertyPlaceholderAutoConfiguration;
import org.springframework.boot.convert.ApplicationConversionService;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import java.time.Duration;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 去重表保留期启动校验（#31）：必须严格大于 Broker 保留期 + 冲销暂存期，否则启动失败。 */
class DedupRetentionTest {
    private final ApplicationContextRunner runner = new ApplicationContextRunner()
        .withConfiguration(AutoConfigurations.of(PropertyPlaceholderAutoConfiguration.class)).withBean(DedupRetention.class)
        // 与 Spring Boot 应用一致：使用 ApplicationConversionService 把 "P30D" 等字符串转换为 Duration。
        .withInitializer(ctx -> ctx.getBeanFactory().setConversionService(ApplicationConversionService.getSharedInstance()));

    @Test void defaultsPass() {
        DedupRetention r = new DedupRetention(Duration.ofDays(30), Duration.ofHours(72), Duration.ofHours(24));
        assertThat(r.retention()).isEqualTo(Duration.ofDays(30));
        runner.run(ctx -> assertThat(ctx).hasNotFailed().hasSingleBean(DedupRetention.class));
    }

    @Test void retentionEqualToFloorFails() {
        assertThatThrownBy(() -> new DedupRetention(Duration.ofHours(96), Duration.ofHours(72), Duration.ofHours(24)))
            .isInstanceOf(IllegalStateException.class).hasMessageContaining("必须大于");
        assertThat(new DedupRetention(Duration.ofHours(96).plusSeconds(1), Duration.ofHours(72), Duration.ofHours(24)).retention())
            .isEqualTo(Duration.ofHours(96).plusSeconds(1));
    }

    @Test void nonPositiveValuesFail() {
        assertThatThrownBy(() -> new DedupRetention(Duration.ZERO, Duration.ofHours(72), Duration.ofHours(24))).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> new DedupRetention(Duration.ofDays(30), Duration.ZERO, Duration.ofHours(24))).isInstanceOf(IllegalStateException.class);
    }

    /** 配置过短时容器启动失败（而不是运行期才发现）。 */
    @Test void startupFailsWhenRetentionTooShort() {
        runner.withPropertyValues("quality.dedup.retention=P3D", "mes.quality.broker-retention=PT72H")
            .run(ctx -> assertThat(ctx).hasFailed().getFailure().rootCause().isInstanceOf(IllegalStateException.class).hasMessageContaining("必须大于"));
        runner.withPropertyValues("quality.dedup.retention=P5D", "mes.quality.broker-retention=PT72H", "quality.pending-reversal.ttl=PT48H")
            .run(ctx -> assertThat(ctx).hasFailed());
    }
}
