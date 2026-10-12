package com.smartmanufacturing.workorder;
import com.smartmanufacturing.api.workorder.HoldReason;
import com.smartmanufacturing.common.BusinessException;
import feign.FeignException;
import feign.Request;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** 下达 / 冻结 / 恢复 / 续约 / 过期的业务回归（H2）；真实并发语义见 *MySqlIT。 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class WorkOrderReleaseIntegrationTest {
    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired WorkOrderReleaseService service;
    @Autowired ReservationRenewalJob renewalJob;
    @MockitoBean MasterdataClient masterdata;
    @MockitoBean DeviceClient devices;
    @MockitoSpyBean MaterialReservationPort materials;
    @MockitoBean org.springframework.security.oauth2.jwt.JwtDecoder decoder;

    @Autowired LocalMaterialReservation stub;
    @BeforeEach void setUp() { ReleaseFixtures.clear(jdbc); ReleaseFixtures.stubMasterdata(masterdata); stub.reset(); }

    private RequestPostProcessor operator() { return jwt().authorities(new SimpleGrantedAuthority("workorder:write"), new SimpleGrantedAuthority("workorder:read")); }
    private int count(String sql, Object... args) { return jdbc.queryForObject(sql, Integer.class, args); }

    /** 下达冻结快照、生成工序任务、按物料汇总预留，并写入审计与发件箱事件。 */
    @Test void releaseSnapshotsAndReserves() throws Exception {
        String id = ReleaseFixtures.createOrder(jdbc, 10);
        mvc.perform(post("/workorders/" + id + "/release").with(operator()).header("Idempotency-Key", "rel-1"))
            .andExpect(status().isOk()).andExpect(header().string("Idempotent-Replayed", "false"))
            .andExpect(jsonPath("$.data.status").value("RELEASED"))
            .andExpect(jsonPath("$.data.bomVersion").value("A01")).andExpect(jsonPath("$.data.routingVersion").value("R01"))
            .andExpect(jsonPath("$.data.bomLineCount").value(4))
            .andExpect(jsonPath("$.data.operations[0].status").value("READY"))
            .andExpect(jsonPath("$.data.operations[1].status").value("PENDING"))
            .andExpect(jsonPath("$.data.reservation.status").value("ACTIVE"))
            .andExpect(jsonPath("$.data.reservation.lines.length()").value(2))
            // 螺栓两处用量 4×10 + 2×10 汇总为 60
            .andExpect(jsonPath("$.data.reservation.lines[?(@.materialCode=='STD-BOLT')].quantity").value(60.0));
        assertThat(jdbc.queryForObject("select status from work_order where id = ?", String.class, id)).isEqualTo("RELEASED");
        assertThat(count("select count(*) from work_order_status_log where order_id = ? and to_status = 'RELEASED'", id)).isEqualTo(1);
        assertThat(count("select count(*) from outbox_event where aggregate_id = ? and event_type = 'WorkOrderReleased' and published_at is null", id)).isEqualTo(1);
    }

    /** 同键重放返回首次结果且不再调用主数据与库存；不同键返回 409。 */
    @Test void releaseIsIdempotent() throws Exception {
        String id = ReleaseFixtures.createOrder(jdbc, 5);
        service.release(id, "k1", "u");
        clearInvocations(masterdata, materials);
        mvc.perform(post("/workorders/" + id + "/release").with(operator()).header("Idempotency-Key", "k1"))
            .andExpect(status().isOk()).andExpect(header().string("Idempotent-Replayed", "true"));
        verifyNoInteractions(masterdata, materials);
        mvc.perform(post("/workorders/" + id + "/release").with(operator()).header("Idempotency-Key", "k2"))
            .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("WORK_ORDER_STATE_CONFLICT"));
        assertThat(count("select count(*) from wo_material_reservation where order_id = ?", id)).isEqualTo(1);
    }

    /** 缺少幂等键 400；无写权限 403；工单不存在 404。 */
    @Test void releaseInputErrors() throws Exception {
        String id = ReleaseFixtures.createOrder(jdbc, 1);
        mvc.perform(post("/workorders/" + id + "/release").with(operator())).andExpect(status().isBadRequest());
        mvc.perform(post("/workorders/" + id + "/release").with(jwt().authorities(new SimpleGrantedAuthority("workorder:read"))).header("Idempotency-Key", "x"))
            .andExpect(status().isForbidden());
        mvc.perform(post("/workorders/nope/release").with(operator()).header("Idempotency-Key", "x")).andExpect(status().isNotFound());
    }

    /** 主数据无生效版本（404）映射为 422，不预留、不改状态。 */
    @Test void missingMasterdataRejectsRelease() throws Exception {
        String id = ReleaseFixtures.createOrder(jdbc, 1);
        var notFound = new FeignException.NotFound("none", Request.create(Request.HttpMethod.GET, "/x", new HashMap<>(), null, StandardCharsets.UTF_8, null), null, null);
        when(masterdata.effectiveRouting(anyString(), anyString())).thenThrow(MasterdataFallbackFactory.map(notFound));
        mvc.perform(post("/workorders/" + id + "/release").with(operator()).header("Idempotency-Key", "x"))
            .andExpect(status().isUnprocessableEntity()).andExpect(jsonPath("$.code").value("RELEASE_MASTERDATA_INVALID"));
        verify(materials, never()).reserve(anyString(), anyString(), any());
        assertThat(jdbc.queryForObject("select status from work_order where id = ?", String.class, id)).isEqualTo("CREATED");
    }

    /** 本地事务失败时补偿释放预留，工单保持 CREATED。 */
    @Test void failedPersistReleasesReservation() {
        String id = ReleaseFixtures.createOrder(jdbc, 1);
        // 消耗工序不在路线中之前已校验；这里模拟快照写入失败：预先占用该工单的工序任务主键。
        jdbc.update("insert into operation_task (order_id, op_no, seq, name, work_center_code, setup_minutes, run_seconds_per_unit, report_point, first_article_required, inspection_point_codes, status) values (?, 'OP10', 1, 'x', 'x', 0, 0, false, false, '', 'READY')", id);
        assertThatThrownBy(() -> service.release(id, "k", "u")).isInstanceOf(org.springframework.dao.DuplicateKeyException.class);
        verify(materials).release(eq("release:" + id + ":k"), anyString());
        assertThat(jdbc.queryForObject("select status from work_order where id = ?", String.class, id)).isEqualTo("CREATED");
        assertThat(count("select count(*) from work_order_lifecycle where order_id = ?", id)).isZero();
    }

    /** 冻结记录 resume_target，多原因并存，全部解除后恢复；同原因重复冻结幂等。 */
    @Test void holdAndResumeWithMultipleReasons() {
        String id = ReleaseFixtures.createOrder(jdbc, 2);
        service.release(id, "k", "u");
        var held = service.hold(id, HoldReason.MANUAL, "盘点", "u");
        assertThat(held.status()).isEqualTo("ON_HOLD");
        assertThat(held.resumeTarget()).isEqualTo("RELEASED");
        long v = service.hold(id, HoldReason.MANUAL, "重复", "u").version();
        assertThat(v).isEqualTo(held.version());
        service.hold(id, HoldReason.LOT_HELD, null, "u");
        var partially = service.resume(id, HoldReason.MANUAL, null, "u");
        assertThat(partially.status()).isEqualTo("ON_HOLD");
        assertThat(partially.holdReasons()).containsExactly("LOT_HELD");
        var resumed = service.resume(id, HoldReason.LOT_HELD, null, "u");
        assertThat(resumed.status()).isEqualTo("RELEASED");
        assertThat(resumed.resumeTarget()).isNull();
        assertThat(resumed.version()).isEqualTo(4);
        assertThat(count("select count(*) from outbox_event where aggregate_id = ? and event_type = 'WorkOrderResumed'", id)).isEqualTo(1);
    }

    /** 未下达的工单不能冻结。 */
    @Test void cannotHoldUnreleasedOrder() {
        String id = ReleaseFixtures.createOrder(jdbc, 2);
        assertThatThrownBy(() -> service.hold(id, HoldReason.MANUAL, null, "u"))
            .isInstanceOfSatisfying(BusinessException.class, e -> assertThat(e.code()).isEqualTo("WORK_ORDER_STATE_CONFLICT"));
    }

    /** 续约作业镜像库存返回的到期时间；同一时间片幂等键相同。 */
    @Test void renewalJobRenewsActiveReservations() {
        String id = ReleaseFixtures.createOrder(jdbc, 2);
        service.release(id, "k", "u");
        assertThat(renewalJob.renewBatch()).isEqualTo(1);
        verify(materials).renew(startsWith("wo-renew:"), anyString());
        assertThat(count("select count(*) from wo_material_reservation where order_id = ? and last_renewed_at is not null", id)).isEqualTo(1);
    }

    /** 过期事件幂等：工单以 MATERIAL_RESERVATION_EXPIRED 冻结；解除该原因时重新预留并恢复。 */
    @Test void expiryHoldsOrderAndResumeReReserves() {
        String id = ReleaseFixtures.createOrder(jdbc, 2);
        String reservationId = service.release(id, "k", "u").view().reservation().reservationId();
        assertThat(service.onReservationExpired(reservationId)).isTrue();
        assertThat(service.onReservationExpired(reservationId)).isFalse();
        var held = service.view(id);
        assertThat(held.status()).isEqualTo("ON_HOLD");
        assertThat(held.holdReasons()).containsExactly("MATERIAL_RESERVATION_EXPIRED");
        assertThat(held.reservation().status()).isEqualTo("EXPIRED");
        // 过期的预留不再续约
        assertThat(renewalJob.renewBatch()).isZero();
        var resumed = service.resume(id, HoldReason.MATERIAL_RESERVATION_EXPIRED, null, "u");
        assertThat(resumed.status()).isEqualTo("RELEASED");
        assertThat(resumed.reservation().status()).isEqualTo("ACTIVE");
        assertThat(resumed.reservation().reservationId()).isNotEqualTo(reservationId);
        assertThat(resumed.reservation().lines()).extracting(l -> l.quantity().stripTrailingZeros())
            .containsExactly(new BigDecimal("2.1"), new BigDecimal("12"));
    }

    /** 续约时库存答复预留已失效，按过期流程冻结工单。 */
    @Test void renewalGoneTriggersExpiry() {
        String id = ReleaseFixtures.createOrder(jdbc, 2);
        service.release(id, "k", "u");
        doThrow(new MaterialReservationPort.ReservationGoneException("x")).when(materials).renew(anyString(), anyString());
        assertThat(renewalJob.renewBatch()).isZero();
        assertThat(service.view(id).status()).isEqualTo("ON_HOLD");
    }

    /** C：预留幂等键 = release:{工单号}:{下达 Idempotency-Key}；预留行带库存返回的 lineId。 */
    @Test void reservationKeyDerivesFromReleaseKey() {
        String id = ReleaseFixtures.createOrder(jdbc, 2);
        var view = service.release(id, "abc-1", "u").view();
        verify(materials).reserve(eq("release:" + id + ":abc-1"), eq(id), anyList());
        assertThat(view.reservation().lines()).allSatisfy(l -> assertThat(l.lineId()).startsWith(view.reservation().reservationId()));
    }

    /** 5：恢复时的唯一约束冲突若并非并发胜者造成（原因仍在），返回 409，不静默吞掉。 */
    @Test void resumeDuplicateWithoutWinnerIsNotSwallowed() {
        String id = ReleaseFixtures.createOrder(jdbc, 2);
        String rid = service.release(id, "k", "u").view().reservation().reservationId();
        service.onReservationExpired(rid);
        // 预先让另一工单占用恢复将得到的预留单号（唯一约束冲突），模拟冲突但没有任何请求解除原因
        String collide = "LR-" + java.util.UUID.nameUUIDFromBytes(("rereserve:" + id + ":2").getBytes(java.nio.charset.StandardCharsets.UTF_8));
        jdbc.update("insert into wo_material_reservation (order_id, seq, reservation_id, status, expires_at) values ('other', 1, ?, 'RELEASED', current_timestamp)", collide);
        assertThatThrownBy(() -> service.resume(id, HoldReason.MATERIAL_RESERVATION_EXPIRED, null, "u"))
            .isInstanceOfSatisfying(BusinessException.class, e -> assertThat(e.code()).isEqualTo("CONCURRENT_MODIFICATION"));
        assertThat(service.view(id).holdReasons()).containsExactly("MATERIAL_RESERVATION_EXPIRED");
        // 新预留已补偿释放
        verify(materials).release(eq("rereserve:" + id + ":2"), anyString());
    }

    /** 3：续约按批循环取尽，批大小小于活动预留数时也能一轮覆盖全部。 */
    @Test void renewalLoopsOverAllBatches() {
        for (int i = 0; i < 5; i++) service.release(ReleaseFixtures.createOrder(jdbc, 1), "k", "u");
        var job = new ReservationRenewalJob(service, java.time.Clock.systemUTC(), java.time.Duration.ofMinutes(5), 2);
        assertThat(job.renewBatch()).isEqualTo(5);
    }

    /** B（HTTP）：预留 409 时响应体 code=MATERIAL_SHORTAGE，data 为缺料明细原样；工单仍为 CREATED。 */
    @Test void shortageReturns409WithDetails() throws Exception {
        String id = ReleaseFixtures.createOrder(jdbc, 2);
        stub.simulateShortage(java.util.List.of(new com.smartmanufacturing.api.workorder.MaterialShortage("RM-STEEL", new BigDecimal("2.1"), new BigDecimal("0.5"), "KG")));
        mvc.perform(post("/workorders/" + id + "/release").with(operator()).header("Idempotency-Key", "s1"))
            .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("MATERIAL_SHORTAGE"))
            .andExpect(jsonPath("$.data[0].materialCode").value("RM-STEEL"))
            .andExpect(jsonPath("$.data[0].requested").value(2.1))
            .andExpect(jsonPath("$.data[0].available").value(0.5))
            .andExpect(jsonPath("$.data[0].uom").value("KG"));
        assertThat(jdbc.queryForObject("select status from work_order where id = ?", String.class, id)).isEqualTo("CREATED");
    }
}
