package com.smartmanufacturing.workorder;
import com.smartmanufacturing.api.workorder.MaterialShortage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import java.math.BigDecimal;
import java.util.List;
import static org.assertj.core.api.Assertions.*;

/**
 * 下达失败分支（真实 MySQL 8.4，使用本地预留替身的故障模拟，不用 Mockito 替换适配器）。
 * 状态映射：评审中的 PLANNED 即本服务的 CREATED（未下达）。
 * <ul>
 *   <li>A：预留成功但本地事务失败 → 工单保持 CREATED、无任何下达数据，且恰好调用一次释放；释放也失败时不覆盖原始错误；</li>
 *   <li>B：预留 409 → 工单保持 CREATED，缺料明细原样返回；</li>
 *   <li>C：预留幂等键为 release:{工单号}:{下达 Idempotency-Key}，同键重放得到同一张预留单。</li>
 * </ul>
 */
@Testcontainers
@SpringBootTest
@ActiveProfiles("test")
class ReleaseCompensationMySqlIT {
    @Container @ServiceConnection
    static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.4");

    @Autowired WorkOrderReleaseService service;
    @Autowired LocalMaterialReservation stub;
    @Autowired JdbcTemplate jdbc;
    @MockitoBean MasterdataClient masterdata;
    @MockitoBean DeviceClient devices;
    @MockitoBean org.springframework.security.oauth2.jwt.JwtDecoder decoder;

    @BeforeEach void setUp() { ReleaseFixtures.clear(jdbc); ReleaseFixtures.stubMasterdata(masterdata); stub.reset(); }

    private String status(String id) { return jdbc.queryForObject("select status from work_order where id = ?", String.class, id); }
    private int rows(String table, String id) { return jdbc.queryForObject("select count(*) from " + table + " where order_id = ?", Integer.class, id); }

    /** 让本地事务在写工序任务时失败：预先占用该工单首道工序的主键。 */
    private void breakLocalTransaction(String id) {
        jdbc.update("insert into operation_task (order_id, op_no, seq, name, work_center_code, setup_minutes, run_seconds_per_unit, report_point, first_article_required, inspection_point_codes, status) values (?, 'OP10', 1, 'x', 'x', 0, 0, false, false, '', 'READY')", id);
    }

    private void assertNotReleased(String id) {
        assertThat(status(id)).isEqualTo("CREATED");
        for (String t : List.of("work_order_lifecycle", "wo_bom_snapshot", "wo_material_reservation", "work_order_status_log"))
            assertThat(rows(t, id)).as(t).isZero();
        assertThat(jdbc.queryForObject("select count(*) from outbox_event where aggregate_id = ?", Integer.class, id)).isZero();
    }

    @Test void localFailureReleasesReservationExactlyOnce() {
        String id = ReleaseFixtures.createOrder(jdbc, 4);
        breakLocalTransaction(id);
        assertThatThrownBy(() -> service.release(id, "k1", "u")).isInstanceOf(DuplicateKeyException.class);
        assertNotReleased(id);
        String key = "release:" + id + ":k1";
        assertThat(stub.calls()).containsExactly("reserve:" + key, "release:" + key);
    }

    @Test void releaseFailureDuringCompensationKeepsOriginalError() {
        String id = ReleaseFixtures.createOrder(jdbc, 4);
        breakLocalTransaction(id);
        stub.simulateReleaseFailure(true);
        // 抛出的是原始的本地事务异常，释放失败只作为 suppressed 附带，预留交由库存 TTL 回收
        assertThatThrownBy(() -> service.release(id, "k1", "u"))
            .isInstanceOf(DuplicateKeyException.class)
            .satisfies(e -> assertThat(e.getSuppressed()).anySatisfy(s -> assertThat(s).hasMessageContaining("模拟：库存释放失败")));
        assertNotReleased(id);
        assertThat(stub.calls()).filteredOn(c -> c.startsWith("release:")).hasSize(1);
    }

    @Test void shortageKeepsOrderCreatedAndReturnsDetailsUnchanged() {
        String id = ReleaseFixtures.createOrder(jdbc, 4);
        var shortages = List.of(new MaterialShortage("RM-STEEL", new BigDecimal("4.2"), new BigDecimal("1.0"), "KG"),
                                new MaterialShortage("STD-BOLT", new BigDecimal("24"), BigDecimal.ZERO, "PCS"));
        stub.simulateShortage(shortages);
        assertThatThrownBy(() -> service.release(id, "k1", "u"))
            .isInstanceOfSatisfying(MaterialShortageException.class, e -> {
                assertThat(e.code()).isEqualTo("MATERIAL_SHORTAGE");
                assertThat(e.status().value()).isEqualTo(409);
                assertThat(e.shortages()).isEqualTo(shortages);
            });
        assertNotReleased(id);
        // 预留未成功，不应释放
        assertThat(stub.calls()).containsExactly("reserve:release:" + id + ":k1");
        // 补货后同一请求可重试成功
        stub.simulateShortage(null);
        assertThat(service.release(id, "k1", "u").view().status()).isEqualTo("RELEASED");
    }

    @Test void reservationKeyIsDerivedFromReleaseKey() {
        String id = ReleaseFixtures.createOrder(jdbc, 4);
        var first = service.release(id, "rel-42", "u").view().reservation();
        // 下达的本地事务提交后，同键重放直接返回首次结果；直接对端口重放同一派生键，库存（替身）返回同一张预留单
        var again = stub.reserve(WorkOrderReleaseService.reservationKey(id, "rel-42"), id, List.of());
        assertThat(again.reservationId()).isEqualTo(first.reservationId());
        assertThat(stub.calls()).first().isEqualTo("reserve:release:" + id + ":rel-42");
        assertThat(service.release(id, "rel-42", "u").replayed()).isTrue();
    }
}
