package com.smartmanufacturing.workorder;

import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

/** 库存客户端配置的默认值与地址选择：空 / 空白取默认，固定地址优先于服务发现。 */
class InventoryClientPropertiesTest {
    /** 全部缺省：服务名、clientId、超时取默认，两个地址都走服务发现。 */
    @Test void defaultsWhenNullOrBlank() {
        for (String empty : new String[] {null, " "}) {
            var p = new InventoryClientProperties(empty, empty, empty, empty, empty, null, null, null, null);
            assertThat(p.inventoryService()).isEqualTo("inventory-service");
            assertThat(p.authService()).isEqualTo("auth-service");
            assertThat(p.clientId()).isEqualTo("workorder-service");
            assertThat(p.connectTimeout()).isEqualTo(Duration.ofSeconds(2));
            assertThat(p.readTimeout()).isEqualTo(Duration.ofSeconds(3));
            assertThat(p.tokenRefreshSkew()).isEqualTo(Duration.ofSeconds(60));
            assertThat(p.inventoryBase()).isEqualTo("http://inventory-service");
            assertThat(p.authBase()).isEqualTo("http://auth-service");
            assertThat(p.discovery()).isTrue();
        }
    }

    /** 显式配置原样保留；两个地址都固定时不需要负载均衡，只固定一个时仍需要。 */
    @Test void explicitValuesAndFixedUrls() {
        var p = new InventoryClientProperties("http://inv:1", "http://auth:2", "inv", "auth", "wo", "secret",
            Duration.ofMillis(100), Duration.ofMillis(200), Duration.ofSeconds(5));
        assertThat(p.inventoryService()).isEqualTo("inv");
        assertThat(p.authService()).isEqualTo("auth");
        assertThat(p.clientId()).isEqualTo("wo");
        assertThat(p.connectTimeout()).isEqualTo(Duration.ofMillis(100));
        assertThat(p.readTimeout()).isEqualTo(Duration.ofMillis(200));
        assertThat(p.tokenRefreshSkew()).isEqualTo(Duration.ofSeconds(5));
        assertThat(p.inventoryBase()).isEqualTo("http://inv:1");
        assertThat(p.authBase()).isEqualTo("http://auth:2");
        assertThat(p.discovery()).isFalse();
        assertThat(new InventoryClientProperties("http://inv:1", null, null, null, null, null, null, null, null).discovery()).isTrue();
        assertThat(new InventoryClientProperties(null, "http://auth:2", null, null, null, null, null, null, null).discovery()).isTrue();
    }
}
