package com.smartmanufacturing.quality;

import org.junit.jupiter.api.Test;
import java.time.Duration;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 回写配置的默认值与启动校验：重试窗口必须小于库存幂等保留期 7 天。 */
class WritebackPropertiesTest {
    private static WritebackProperties withWindow(Duration window) {
        return new WritebackProperties(window, null, null, null, null, null, null, null, null, null);
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

    @Test void explicitValuesAndValidation() {
        var c = new WritebackProperties.Client("q", "secret", "s", "http://inv:1", "http://auth:2", "inv-svc", "auth-svc",
            Duration.ofSeconds(1), Duration.ofSeconds(2), Duration.ofSeconds(30));
        assertThat(c.configured()).isTrue();
        assertThat(c.inventoryBase()).isEqualTo("http://inv:1");
        assertThat(c.authBase()).isEqualTo("http://auth:2");
        var blank = new WritebackProperties.Client(" ", " ", " ", "", "", " ", " ", null, null, null);
        assertThat(blank.configured()).isFalse();
        assertThat(blank.clientId()).isEqualTo("quality-service");
        assertThat(blank.authBase()).isEqualTo("http://auth-service");
        assertThat(blank.inventoryBase()).isEqualTo("http://inventory-service");
        var p = new WritebackProperties(Duration.ofDays(1), Duration.ofSeconds(5), 10, 3, Duration.ofSeconds(1),
            Duration.ofMinutes(1), Duration.ofSeconds(30), Duration.ofDays(2), c, 0);
        assertThat(p.client()).isSameAs(c);
        assertThat(p.maxUnknown404Retries()).isZero();
        assertThatThrownBy(() -> new WritebackProperties(null, null, 0, null, null, null, null, null, null, null))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new WritebackProperties(null, null, null, 0, null, null, null, null, null, null))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new WritebackProperties(null, null, null, null, null, null, null, null, null, -1))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new WritebackProperties(Duration.ofSeconds(-1), null, null, null, null, null, null, null, null, null))
            .isInstanceOf(IllegalArgumentException.class);
    }
}
