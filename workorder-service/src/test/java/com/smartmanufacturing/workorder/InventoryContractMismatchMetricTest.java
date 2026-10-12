package com.smartmanufacturing.workorder;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.smartmanufacturing.api.workorder.ReservationLineView;
import com.smartmanufacturing.common.BusinessException;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;
import java.math.BigDecimal;
import java.util.List;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.mock;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.*;
import static org.springframework.test.web.client.response.MockRestResponseCreators.*;

/**
 * 契约不一致计数：库存返回 422 uom-mismatch 或响应结构不符合契约时，
 * mes_workorder_inventory_contract_mismatch_total{operation} 递增；5xx / 网络错误不计入。
 */
class InventoryContractMismatchMetricTest {
    private SimpleMeterRegistry registry;
    private MockRestServiceServer server;
    private InventoryReservationClient client;

    @BeforeEach void setUp() {
        registry = new SimpleMeterRegistry();
        RestClient.Builder builder = RestClient.builder().baseUrl("http://inventory");
        server = MockRestServiceServer.bindTo(builder).build();
        client = new InventoryReservationClient(builder.build(), mock(ServiceTokenProvider.class), new ObjectMapper(),
            new InventoryClientMetrics(registry, mock(JdbcTemplate.class)));
    }

    private double mismatch(String op) {
        return registry.find("mes.workorder.inventory.contract.mismatch").tag("operation", op).counter().count();
    }

    private static List<ReservationLineView> lines() {
        return List.of(new ReservationLineView(null, "M-001", new BigDecimal("2"), "PCS"));
    }

    /** 启动即以 0 预注册四种操作的契约不一致序列，以及释放结果 contract_mismatch。 */
    @Test void preRegisteredAtZero() {
        for (String op : List.of("reserve", "renew", "release", "get"))
            assertThat(registry.find("mes.workorder.inventory.contract.mismatch").tag("operation", op).counter())
                .as(op).isNotNull().satisfies(c -> assertThat(c.count()).isZero());
        assertThat(registry.find("mes.workorder.reservation.release").tag("result", "contract_mismatch").counter()).isNotNull()
            .satisfies(c -> assertThat(c.count()).isZero());
    }

    /** 422 uom-mismatch：仍按业务错误返回调用方，同时计 contract_mismatch。 */
    @Test void uomMismatchCounted() {
        server.expect(requestTo("http://inventory/inventory/reservations")).andExpect(method(HttpMethod.POST))
            .andRespond(withStatus(HttpStatus.UNPROCESSABLE_ENTITY).contentType(MediaType.APPLICATION_PROBLEM_JSON)
                .body("{\"type\":\"https://smart-manufacturing.example/problems/uom-mismatch\",\"status\":422,\"detail\":\"单位不一致\"}"));
        assertThatThrownBy(() -> client.reserve("k1", "WO-1", lines())).isInstanceOf(BusinessException.class)
            .satisfies(e -> assertThat(((BusinessException) e).code()).isEqualTo(WorkOrderProblemType.UOM_MISMATCH.code()));
        assertThat(mismatch("reserve")).isEqualTo(1);
    }

    /** 2xx 但响应结构不符合契约（expiresAt 不是时间）：转为 InventoryContractException 并计数。 */
    @Test void malformedBodyCounted() {
        server.expect(requestTo("http://inventory/inventory/reservations/R-1/renew"))
            .andRespond(withSuccess("{\"expiresAt\":\"not-a-time\"}", MediaType.APPLICATION_JSON));
        assertThatThrownBy(() -> client.renew("k2", "R-1")).isInstanceOf(InventoryContractException.class);
        assertThat(mismatch("renew")).isEqualTo(1);
    }

    /** 释放 2xx 但状态不是 RELEASED / EXPIRED：计入 release。 */
    @Test void unexpectedReleaseStatusCounted() {
        server.expect(requestTo("http://inventory/inventory/reservations/R-1/release"))
            .andRespond(withSuccess("{\"status\":\"ACTIVE\"}", MediaType.APPLICATION_JSON));
        assertThatThrownBy(() -> client.release("k3", "R-1", MaterialReservationPort.ReleaseReason.WORK_ORDER_CANCELLED))
            .isInstanceOf(InventoryContractException.class);
        assertThat(mismatch("release")).isEqualTo(1);
    }

    /** 5xx 是可用性问题，不计入 contract_mismatch。 */
    @Test void serverErrorNotCounted() {
        server.expect(requestTo("http://inventory/inventory/reservations/R-1/release"))
            .andRespond(withStatus(HttpStatus.SERVICE_UNAVAILABLE));
        assertThatThrownBy(() -> client.release("k4", "R-1", MaterialReservationPort.ReleaseReason.WORK_ORDER_CANCELLED))
            .isInstanceOf(InventoryUnavailableException.class);
        assertThat(mismatch("release")).isZero();
    }
}
