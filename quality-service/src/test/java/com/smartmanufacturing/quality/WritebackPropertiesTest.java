package com.smartmanufacturing.quality;

import org.junit.jupiter.api.Test;
import java.time.Duration;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 回写配置的默认值与启动校验：重试窗口必须小于库存幂等保留期 7 天。 */
class WritebackPropertiesTest {
    private static WritebackProperties withWindow(Duration window) {
        return new WritebackProperties(window, null, null, null, null, null, null, null, null);
    }

    @Test void defaultsMatchDesign() {
        WritebackProperties p = withWindow(null);
        assertThat(p.retryWindow()).isEqualTo(Duration.ofDays(6));
        assertThat(p.interval()).isEqualTo(Duration.ofSeconds(2));
        assertThat(p.batchSize()).isEqualTo(50);
        assertThat(p.maxAttempts()).isEqualTo(20);
        assertThat(p.maxBackoff()).isEqualTo(Duration.ofMinutes(5));
        assertThat(p.retention()).isEqualTo(Duration.ofDays(30));
        assertThat(p.client().clientId()).isEqualTo("quality-service");
        assertThat(p.client().scope()).isEqualTo("inventory:qc-hold");
        assertThat(p.client().configured()).isFalse();
        assertThat(p.client().inventoryBase()).isEqualTo("http://inventory-service");
    }

    @Test void retryWindowMustBeShorterThanInventoryRetention() {
        assertThatThrownBy(() -> withWindow(Duration.ofDays(7))).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("retry-window");
        assertThatThrownBy(() -> withWindow(Duration.ZERO)).isInstanceOf(IllegalArgumentException.class);
        assertThat(withWindow(Duration.ofDays(7).minusSeconds(1)).retryWindow()).isEqualTo(Duration.ofDays(7).minusSeconds(1));
    }
}
