package com.smartmanufacturing.workorder;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.smartmanufacturing.api.workorder.CancelWorkOrderRequest;
import com.smartmanufacturing.api.workorder.ReservationLineView;
import com.smartmanufacturing.common.BusinessException;
import io.micrometer.core.instrument.MeterRegistry;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import java.math.BigDecimal;
import java.util.List;
import static org.assertj.core.api.Assertions.*;

/**
 * 库存客户端与工单取消（真实 MySQL 8.4 + HTTP 库存替身 {@link InventoryStub}，走生产的 {@link InventoryReservationClient}，不用 Mockito 替换端口）：
 * <ul>
 *   <li>库存宕机时取消成功、释放任务退避；库存恢复后释放完成，库存侧预留为 RELEASED、原因 WORK_ORDER_CANCELLED、键 cancel-{工单号}-{预留单号}；</li>
 *   <li>重复取消：同键重放返回首次结果，换键 409 workorder-cancelled，均不再次释放；</li>
 *   <li>预留已扣减：任务停止、写审计、计入 reservation_consumed；401/403 退避重试并计入告警指标；</li>
 *   <li>Problem 映射：insufficient-stock（从未入库 available=0）→ 缺料、uom-mismatch → 422、非持有方续约 403；</li>
 *   <li>预留事件：按 eventId 去重、按不连续的 aggregateVersion 丢弃过时事件。</li>
 * </ul>
 */
@Testcontainers
@SpringBootTest
@ActiveProfiles("test")
class InventoryCancelMySqlIT {
    @Container @ServiceConnection
    static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.4");
    static final InventoryStub INVENTORY = new InventoryStub();
    static { INVENTORY.start(); }

    @DynamicPropertySource static void props(DynamicPropertyRegistry r) {
        r.add("manufacturing.workorder.material-reservation", () -> "inventory");
        r.add("manufacturing.workorder.inventory.inventory-url", INVENTORY::url);
        r.add("manufacturing.workorder.inventory.auth-url", INVENTORY::url);
        r.add("manufacturing.workorder.inventory.client-secret", () -> InventoryStub.SECRET);
        // 不连 RocketMQ：事件处理由用例直接调用 ReservationEventHandler
        r.add("manufacturing.workorder.reservation-events.enabled", () -> "false");
    }

    @AfterAll static void shutdown() { INVENTORY.stop(); }

    @Autowired WorkOrderReleaseService releases;
    @Autowired OperationReportingService commands;
    @Autowired ReservationReleaseWorker worker;
    @Autowired ReservationEventHandler events;
    @Autowired MaterialReservationPort port;
    @Autowired JdbcTemplate jdbc;
    @Autowired MeterRegistry registry;
    @Autowired ObjectMapper mapper;
    @MockitoBean MasterdataClient masterdata;
    @MockitoBean DeviceClient devices;
    @MockitoBean org.springframework.security.oauth2.jwt.JwtDecoder decoder;

    @BeforeEach void setUp() {
        ReleaseFixtures.clear(jdbc); ReleaseFixtures.stubMasterdata(masterdata);
        INVENTORY.stop(); INVENTORY.reset(); INVENTORY.start();
    }

    private String released() {
        String id = ReleaseFixtures.createOrder(jdbc, 2);
        releases.release(id, "rel-1", "planner");
        return id;
    }
    private String reservationOf(String id) {
        return jdbc.queryForObject("select reservation_id from wo_material_reservation where order_id = ? order by seq desc limit 1", String.class, id);
    }
    private String mirror(String rid) { return jdbc.queryForObject("select status from wo_material_reservation where reservation_id = ?", String.class, rid); }
    private String status(String id) { return jdbc.queryForObject("select status from work_order where id = ?", String.class, id); }
    private String task(String id) { return jdbc.queryForObject("select status from wo_reservation_release_task where order_id = ?", String.class, id); }
    /** 跳过退避等待，让任务立即到期。 */
    private void due() { jdbc.update("update wo_reservation_release_task set next_attempt_at = null"); }
    private double counter(String name, String tag, String value) {
        var c = registry.find(name).tag(tag, value).counter();
        return c == null ? 0 : c.count();
    }

    @Test void cancelWhileInventoryDownIsReleasedAfterRecovery() throws Exception {
        String id = released();
        String rid = reservationOf(id);
        assertThat(INVENTORY.reservations.get(rid).path("status").asText()).isEqualTo("ACTIVE");

        INVENTORY.stop(); // 库存宕机
        var r = commands.cancel(id, new CancelWorkOrderRequest("客户撤单"), "cancel-1", "planner");
        assertThat(r.status()).isEqualTo(200);
        var data = mapper.readTree(r.body()).path("data");
        assertThat(data.path("status").asText()).isEqualTo("CANCELLED");
        assertThat(data.path("previousStatus").asText()).isEqualTo("RELEASED");
        assertThat(data.path("reservationId").asText()).isEqualTo(rid);
        assertThat(status(id)).isEqualTo("CANCELLED");
        assertThat(mirror(rid)).isEqualTo("RELEASING");
        // 同一事务写入了取消事件
        assertThat(jdbc.queryForObject("select count(*) from outbox_event where aggregate_id = ? and event_type = 'WorkOrderCancelled'", Integer.class, id)).isEqualTo(1);

        double unavailable = counter("mes.workorder.reservation.release", "result", "unavailable");
        assertThat(worker.runBatch()).isZero();
        assertThat(task(id)).isEqualTo("PENDING");
        assertThat(jdbc.queryForObject("select attempts from wo_reservation_release_task where order_id = ?", Integer.class, id)).isEqualTo(1);
        assertThat(jdbc.queryForObject("select next_attempt_at is not null from wo_reservation_release_task where order_id = ?", Boolean.class, id)).isTrue();
        assertThat(counter("mes.workorder.reservation.release", "result", "unavailable")).isEqualTo(unavailable + 1);
        // 退避期内不会重试
        assertThat(worker.runBatch()).isZero();
        assertThat(jdbc.queryForObject("select attempts from wo_reservation_release_task where order_id = ?", Integer.class, id)).isEqualTo(1);

        INVENTORY.start(); // 库存恢复
        due();
        assertThat(worker.runBatch()).isEqualTo(1);
        assertThat(task(id)).isEqualTo("DONE");
        assertThat(mirror(rid)).isEqualTo("RELEASED");
        assertThat(INVENTORY.reservations.get(rid).path("status").asText()).isEqualTo("RELEASED");
        assertThat(INVENTORY.releaseReasons.get(rid)).isEqualTo("WORK_ORDER_CANCELLED");
        assertThat(INVENTORY.calls).contains("release:cancel-" + id + "-" + rid);
        assertThat(jdbc.queryForObject("select count(*) from wo_inventory_audit where order_id = ? and event = 'RELEASE_RELEASED'", Integer.class, id)).isEqualTo(1);
    }

    @Test void repeatCancelReplaysOrConflictsAndNeverReleasesTwice() {
        String id = released();
        var first = commands.cancel(id, null, "cancel-1", "planner");
        var replay = commands.cancel(id, null, "cancel-1", "planner");
        assertThat(replay.replayed()).isTrue();
        assertThat(replay.body()).isEqualTo(first.body());
        assertThatThrownBy(() -> commands.cancel(id, null, "cancel-2", "planner"))
            .isInstanceOfSatisfying(BusinessException.class, e -> assertThat(e.code()).isEqualTo(WorkOrderProblemType.WORKORDER_CANCELLED.code()));
        assertThat(jdbc.queryForObject("select count(*) from wo_reservation_release_task where order_id = ?", Integer.class, id)).isEqualTo(1);

        assertThat(worker.runBatch()).isEqualTo(1);
        assertThatThrownBy(() -> commands.cancel(id, null, "cancel-3", "planner")).isInstanceOf(BusinessException.class);
        assertThat(worker.runBatch()).isZero();
        assertThat(INVENTORY.calls).filteredOn(c -> c.startsWith("release:")).hasSize(1);
        // 取消后的下达与报工同样返回 workorder-cancelled
        assertThatThrownBy(() -> releases.release(id, "rel-2", "planner"))
            .isInstanceOfSatisfying(BusinessException.class, e -> assertThat(e.code()).isEqualTo(WorkOrderProblemType.WORKORDER_CANCELLED.code()));
    }

    @Test void createdOrderWithoutReservationCancelsWithoutTask() {
        String id = ReleaseFixtures.createOrder(jdbc, 1);
        var r = commands.cancel(id, null, "cancel-1", "planner");
        assertThat(r.body()).contains("\"previousStatus\":\"CREATED\"").contains("\"reservationId\":null");
        assertThat(jdbc.queryForObject("select count(*) from wo_reservation_release_task", Integer.class)).isZero();
    }

    @Test void inProgressOrderCannotBeCancelled() {
        String id = released();
        jdbc.update("update work_order set status = 'IN_PROGRESS' where id = ?", id);
        assertThatThrownBy(() -> commands.cancel(id, null, "cancel-1", "planner"))
            .isInstanceOfSatisfying(BusinessException.class, e -> assertThat(e.code()).isEqualTo(WorkOrderProblemType.WORK_ORDER_STATE_CONFLICT.code()));
    }

    @Test void consumedReservationStopsTaskWithAuditAndAlert() {
        String id = released();
        String rid = reservationOf(id);
        INVENTORY.reservations.get(rid).put("status", "CONSUMED");
        commands.cancel(id, null, "cancel-1", "planner");
        double before = counter("mes.workorder.reservation.release", "result", "reservation_consumed");
        assertThat(worker.runBatch()).isEqualTo(1);
        assertThat(task(id)).isEqualTo("STOPPED");
        assertThat(mirror(rid)).isEqualTo("CONSUMED");
        assertThat(jdbc.queryForObject("select count(*) from wo_inventory_audit where order_id = ? and event = 'RESERVATION_CONSUMED'", Integer.class, id)).isEqualTo(1);
        assertThat(counter("mes.workorder.reservation.release", "result", "reservation_consumed")).isEqualTo(before + 1);
        due();
        assertThat(worker.runBatch()).isZero(); // 已停止，不再重试
    }

    @Test void authFailureRetriesAndAlerts() {
        String id = released();
        commands.cancel(id, null, "cancel-1", "planner");
        INVENTORY.releaseFailure = 403;
        double before = counter("mes.workorder.inventory.auth.failure", "operation", "release");
        assertThat(worker.runBatch()).isZero();
        assertThat(task(id)).isEqualTo("PENDING");
        assertThat(counter("mes.workorder.inventory.auth.failure", "operation", "release")).isEqualTo(before + 1);
        INVENTORY.releaseFailure = 503;
        due();
        assertThat(worker.runBatch()).isZero();
        INVENTORY.releaseFailure = null;
        due();
        assertThat(worker.runBatch()).isEqualTo(1);
        assertThat(task(id)).isEqualTo("DONE");
    }

    @Test void problemTypesAreMapped() {
        var never = List.of(new ReservationLineView(null, "NEVER-STOCKED", new BigDecimal("3"), "kg"));
        assertThatThrownBy(() -> port.reserve("k-never", "WO-X", never))
            .isInstanceOfSatisfying(MaterialShortageException.class, e -> {
                assertThat(e.shortages()).singleElement().satisfies(s -> {
                    assertThat(s.materialCode()).isEqualTo("NEVER-STOCKED");
                    assertThat(s.available()).isEqualByComparingTo("0");
                    assertThat(s.requested()).isEqualByComparingTo("3");
                });
            });
        var badUom = List.of(new ReservationLineView(null, "RM-STEEL", new BigDecimal("1.5"), "BAD-UOM"));
        assertThatThrownBy(() -> port.reserve("k-uom", "WO-X", badUom))
            .isInstanceOfSatisfying(BusinessException.class, e -> {
                assertThat(e.code()).isEqualTo(WorkOrderProblemType.UOM_MISMATCH.code());
                assertThat(e.status().value()).isEqualTo(422);
            });
        String id = released();
        INVENTORY.renewForbidden = true; // 非持有方续约
        assertThatThrownBy(() -> port.renew("k-renew", reservationOf(id)))
            .isInstanceOfSatisfying(InventoryAuthException.class, e -> assertThat(e.httpStatus()).isEqualTo(403));
        INVENTORY.renewForbidden = false;
        // 续约以响应中的 expiresAt 为准
        assertThat(port.renew("k-renew-2", reservationOf(id))).isAfter(java.time.Instant.now().plusSeconds(800));
        INVENTORY.stop();
        assertThatThrownBy(() -> port.renew("k-renew-3", reservationOf(id))).isInstanceOf(InventoryUnavailableException.class)
            .isInstanceOfSatisfying(BusinessException.class, e -> assertThat(e.status().value()).isEqualTo(503));
    }

    @Test void eventsAreDedupedAndStaleVersionsDropped() {
        String id = released();
        String rid = reservationOf(id);
        String created = event("e1", "ReservationCreated", rid, 1);
        assertThat(events.handle("ReservationCreated", created)).isEqualTo("applied");
        assertThat(events.handle("ReservationCreated", created)).isEqualTo("duplicate");
        // 版本不连续：7 > 1 直接处理；随后到达的旧版本 4 被丢弃
        assertThat(events.handle("ReservationReleased", event("e2", "ReservationReleased", rid, 7))).isEqualTo("applied");
        assertThat(mirror(rid)).isEqualTo("RELEASED");
        assertThat(events.handle("ReservationExpired", event("e3", "ReservationExpired", rid, 4))).isEqualTo("stale");
        assertThat(status(id)).isEqualTo("RELEASED"); // 过时的过期事件没有冻结工单
        assertThat(events.handle("ReservationExpired", "{\"eventType\":\"ReservationExpired\"}")).isEqualTo("invalid");
        assertThat(events.handle("ReservationCreated", event("e4", "ReservationCreated", "RSV-unknown", 1))).isEqualTo("unknown_reservation");
    }

    @Test void expiredEventHoldsActiveOrder() {
        String id = released();
        String rid = reservationOf(id);
        assertThat(events.handle("ReservationExpired", event("e1", "ReservationExpired", rid, 3))).isEqualTo("applied");
        assertThat(mirror(rid)).isEqualTo("EXPIRED");
        assertThat(status(id)).isEqualTo("ON_HOLD");
    }

    private static String event(String eventId, String type, String rid, long version) {
        return "{\"eventId\":\"" + eventId + "\",\"eventType\":\"" + type + "\",\"reservationId\":\"" + rid + "\",\"workOrderNo\":\"WO\",\"status\":\"ACTIVE\",\"aggregateVersion\":" + version
            + ",\"occurredAt\":\"2026-10-12T00:00:00Z\",\"reason\":\"CALLER_REQUEST\",\"expiresAt\":\"2026-10-12T00:15:00Z\",\"lines\":[]}";
    }
}
