package com.smartmanufacturing.workorder;
import com.smartmanufacturing.api.ApiResponse;
import com.smartmanufacturing.api.masterdata.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.when;

/**
 * 真实 MySQL 8.4 数量精度：6 位小数（库存契约 / V2 DECIMAL(18,6)）经下达写入 BOM 快照与预留行，读回后无精度损失。
 * H2 对 DECIMAL 的舍入与 MySQL 不同，因此在 *IT 中验证。
 */
@Testcontainers
@SpringBootTest
@ActiveProfiles("test")
class QuantityPrecisionMySqlIT {
    @Container @ServiceConnection
    static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.4");

    @Autowired WorkOrderReleaseService service;
    @Autowired JdbcTemplate jdbc;
    @MockitoBean MasterdataClient masterdata;
    @MockitoBean DeviceClient devices;
    @MockitoBean org.springframework.security.oauth2.jwt.JwtDecoder decoder;

    /** 单行 BOM：用量 0.123457（6 位小数），需求 3 台 → 0.370371；另一行 999999999999.999999 验证 DECIMAL(18,6) 上限。 */
    @BeforeEach void setUp() {
        ReleaseFixtures.clear(jdbc);
        String p = ReleaseFixtures.PRODUCT;
        when(masterdata.explode(eq(p), anyString(), any())).thenReturn(ApiResponse.ok(new BomExplosion(p, "A01", BigDecimal.valueOf(3), LocalDate.now(), List.of(
            new BomExplosionLine(1, p + "/RM-FINE", p, "RM-FINE", new BigDecimal("0.123457"), new BigDecimal("0.370371"), "KG", IssueMode.PICK, null, true, "A01"),
            new BomExplosionLine(1, p + "/RM-MAX", p, "RM-MAX", new BigDecimal("1.000000"), new BigDecimal("999999999999.999999"), "G", IssueMode.PICK, null, true, "A01")))));
        when(masterdata.effectiveRouting(eq(p), anyString())).thenReturn(ApiResponse.ok(ReleaseFixtures.routing()));
    }

    @Test void sixDecimalQuantitiesRoundTripWithoutLoss() {
        String id = ReleaseFixtures.createOrder(jdbc, 3);
        var view = service.release(id, "k", "u").view();

        BigDecimal qtyPer = jdbc.queryForObject("select qty_per from wo_bom_snapshot where order_id = ? and component_code = 'RM-FINE'", BigDecimal.class, id);
        BigDecimal required = jdbc.queryForObject("select required_qty from wo_bom_snapshot where order_id = ? and component_code = 'RM-FINE'", BigDecimal.class, id);
        BigDecimal reserved = jdbc.queryForObject("select quantity from wo_material_reservation_line where reservation_id = ? and material_code = 'RM-FINE'", BigDecimal.class, view.reservation().reservationId());
        BigDecimal max = jdbc.queryForObject("select quantity from wo_material_reservation_line where reservation_id = ? and material_code = 'RM-MAX'", BigDecimal.class, view.reservation().reservationId());

        // 字符串比较：值与 scale 都必须一致（toPlainString 不受 compareTo 宽松相等影响）
        assertThat(qtyPer.toPlainString()).isEqualTo("0.123457");
        assertThat(required.toPlainString()).isEqualTo("0.370371");
        assertThat(reserved.toPlainString()).isEqualTo("0.370371");
        assertThat(max.toPlainString()).isEqualTo("999999999999.999999");
        // 视图读回同样无损
        assertThat(view.reservation().lines()).extracting(l -> l.quantity().toPlainString())
            .containsExactlyInAnyOrder("0.370371", "999999999999.999999");
    }
}
