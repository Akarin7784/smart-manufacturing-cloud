package com.smartmanufacturing.workorder;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 库存预留事件处理（H2，直接驱动 handler）：格式校验、eventId 去重、aggregateVersion 丢弃过时事件、未知预留单，
 * 以及 Released / Consumed / Expired / Created 对镜像状态的影响（Expired 使进行中的工单冻结）。
 */
@SpringBootTest
@ActiveProfiles("test")
class ReservationEventHandlerTest {
    @Autowired ReservationEventHandler handler;
    @Autowired JdbcTemplate jdbc;
    @Autowired WorkOrderReleaseService release;
    @Autowired LocalMaterialReservation stub;
    @MockitoBean MasterdataClient masterdata;
    @MockitoBean DeviceClient devices;
    @MockitoBean org.springframework.security.oauth2.jwt.JwtDecoder decoder;

    @BeforeEach void setUp() { ReleaseFixtures.clear(jdbc); ReleaseFixtures.stubMasterdata(masterdata); stub.reset(); }

    private String releasedReservation(String[] orderOut) {
        String id = ReleaseFixtures.createOrder(jdbc, 5);
        release.release(id, "rel-" + id, "planner");
        orderOut[0] = id;
        return jdbc.queryForObject("select reservation_id from wo_material_reservation where order_id = ?", String.class, id);
    }

    private static String body(String eventId, String type, String reservationId, Object version) {
        return "{\"eventId\":\"" + eventId + "\",\"eventType\":\"" + type + "\",\"reservationId\":\"" + reservationId + "\",\"aggregateVersion\":" + version + "}";
    }
    private String mirror(String reservationId) {
        return jdbc.queryForObject("select status from wo_material_reservation where reservation_id = ?", String.class, reservationId);
    }

    /** 非 JSON、tag 未知、tag 与 eventType 不符、缺字段、版本非数字：invalid。 */
    @Test void invalidEvents() {
        assertThat(handler.handle("ReservationReleased", "not json")).isEqualTo("invalid");
        assertThat(handler.handle("Unknown", body("e1", "ReservationReleased", "R1", 1))).isEqualTo("invalid");
        assertThat(handler.handle("ReservationReleased", body("e1", "ReservationConsumed", "R1", 1))).isEqualTo("invalid");
        assertThat(handler.handle("ReservationReleased", "{\"eventType\":\"ReservationReleased\",\"reservationId\":\"R1\",\"aggregateVersion\":1}")).isEqualTo("invalid");
        assertThat(handler.handle("ReservationReleased", "{\"eventId\":\"e\",\"eventType\":\"ReservationReleased\",\"aggregateVersion\":1}")).isEqualTo("invalid");
        assertThat(handler.handle("ReservationReleased", body("e1", "ReservationReleased", "R1", "\"x\""))).isEqualTo("invalid");
        assertThat(handler.handle("ReservationReleased", "x".repeat(400))).isEqualTo("invalid");
        assertThat(handler.tags()).contains("ReservationCreated", "ReservationReleased", "ReservationConsumed", "ReservationExpired");
    }

    /** 未知预留单：记为已消费后忽略；同 eventId 重投：duplicate。 */
    @Test void unknownAndDuplicate() {
        String e = UUID.randomUUID().toString();
        assertThat(handler.handle("ReservationReleased", body(e, "ReservationReleased", "NOPE", 1))).isEqualTo("unknown_reservation");
        assertThat(handler.handle("ReservationReleased", body(e, "ReservationReleased", "NOPE", 1))).isEqualTo("duplicate");
    }

    /** Created 只推进版本；过时版本丢弃；Released 使镜像终态；之后的 Consumed（更高版本）不再改动已终态的镜像。 */
    @Test void versionOrderingAndTerminalStates() {
        String[] order = new String[1];
        String r = releasedReservation(order);
        assertThat(handler.handle("ReservationCreated", body(UUID.randomUUID().toString(), "ReservationCreated", r, 3))).isEqualTo("applied");
        assertThat(mirror(r)).isEqualTo("ACTIVE");
        assertThat(handler.handle("ReservationReleased", body(UUID.randomUUID().toString(), "ReservationReleased", r, 3))).isEqualTo("stale");
        assertThat(handler.handle("ReservationReleased", body(UUID.randomUUID().toString(), "ReservationReleased", r, 7))).isEqualTo("applied");
        assertThat(mirror(r)).isEqualTo("RELEASED");
        assertThat(handler.handle("ReservationConsumed", body(UUID.randomUUID().toString(), "ReservationConsumed", r, 9))).isEqualTo("applied");
        assertThat(mirror(r)).isEqualTo("RELEASED");
    }

    /** Consumed：ACTIVE → CONSUMED。 */
    @Test void consumed() {
        String[] order = new String[1];
        String r = releasedReservation(order);
        assertThat(handler.handle("ReservationConsumed", body(UUID.randomUUID().toString(), "ReservationConsumed", r, 2))).isEqualTo("applied");
        assertThat(mirror(r)).isEqualTo("CONSUMED");
    }

    /** Expired：ACTIVE 的镜像置 EXPIRED，已下达工单以 MATERIAL_RESERVATION_EXPIRED 冻结；重投（duplicate）再次调用也幂等。 */
    @Test void expiredHoldsOrder() {
        String[] order = new String[1];
        String r = releasedReservation(order);
        String e = UUID.randomUUID().toString();
        assertThat(handler.handle("ReservationExpired", body(e, "ReservationExpired", r, 2))).isEqualTo("applied");
        assertThat(mirror(r)).isEqualTo("EXPIRED");
        assertThat(jdbc.queryForObject("select status from work_order where id = ?", String.class, order[0])).isEqualTo("ON_HOLD");
        assertThat(handler.handle("ReservationExpired", body(e, "ReservationExpired", r, 2))).isEqualTo("duplicate");
        assertThat(jdbc.queryForObject("select count(*) from work_order_hold_reason where order_id = ?", Integer.class, order[0])).isEqualTo(1);
    }

    /** Expired 遇到 RELEASING（已取消 / 撤回、释放进行中）：镜像置 EXPIRED，不冻结工单。 */
    @Test void expiredWhileReleasing() {
        String[] order = new String[1];
        String r = releasedReservation(order);
        jdbc.update("update wo_material_reservation set status = 'RELEASING' where reservation_id = ?", r);
        jdbc.update("update work_order set status = 'CANCELLED' where id = ?", order[0]);
        assertThat(handler.handle("ReservationExpired", body(UUID.randomUUID().toString(), "ReservationExpired", r, 2))).isEqualTo("applied");
        assertThat(mirror(r)).isEqualTo("EXPIRED");
        assertThat(jdbc.queryForObject("select status from work_order where id = ?", String.class, order[0])).isEqualTo("CANCELLED");
    }
}
