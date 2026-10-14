package com.smartmanufacturing.workorder;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import feign.FeignException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * M1 PR2（H2，经 HTTP 入口）：工单关闭（质量服务 NCR 校验、质量服务不可用拒绝关闭）、撤回下达与重新下达、
 * 工单列表分页 / 过滤、主数据引用检查、进度附带工序名称与工作中心，以及 #69（冲销后下游 READY 退回 PENDING）。
 * 工艺快照：OP10（WC-CNC）→ OP20（WC-ASM）。
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class WorkOrderCloseWithdrawIntegrationTest {
    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired ObjectMapper mapper;
    @Autowired WorkOrderReleaseService release;
    @Autowired LocalMaterialReservation stub;
    @MockitoBean MasterdataClient masterdata;
    @MockitoBean DeviceClient devices;
    @MockitoBean QualityClient quality;
    @MockitoBean org.springframework.security.oauth2.jwt.JwtDecoder decoder;

    @BeforeEach void setUp() {
        ReleaseFixtures.clear(jdbc);
        ReleaseFixtures.stubMasterdata(masterdata);
        stub.reset();
        noNcr();
    }

    // ------------------------------------------------------------------ 夹具

    private static RequestPostProcessor user(String subject, String... authorities) {
        return jwt().jwt(j -> j.subject(subject)).authorities(Arrays.stream(authorities).map(a -> (GrantedAuthority) new SimpleGrantedAuthority(a)).toList());
    }
    private static RequestPostProcessor writer() { return user("writer-1", "workorder:read", "workorder:write", "quality:read"); }
    private static RequestPostProcessor reader() { return user("reader-1", "workorder:read"); }

    private void noNcr() {
        when(quality.ncrs(anyString(), anyString(), anyInt(), anyInt())).thenReturn(new QualityClient.NcrPage(List.of(), 0, 1, 0));
    }

    private String released(int qty) {
        String id = ReleaseFixtures.createOrder(jdbc, qty);
        release.release(id, "rel-" + id, "planner");
        return id;
    }

    private MvcResult call(RequestPostProcessor who, String path, String body, String key) throws Exception {
        var req = post(path).with(who).contentType(MediaType.APPLICATION_JSON);
        if (body != null) req.content(body);
        if (key != null) req.header("Idempotency-Key", key);
        return mvc.perform(req).andReturn();
    }
    private MvcResult call(String path, String body) throws Exception { return call(writer(), path, body, UUID.randomUUID().toString()); }

    private MvcResult report(String id, String opNo, String good) throws Exception {
        return call("/workorders/" + id + "/operations/" + opNo + "/reports", "{\"goodQty\":\"" + good + "\",\"defectQty\":\"0\",\"scrapQty\":\"0\",\"uom\":\"PCS\",\"laborHours\":\"0\"}");
    }

    private String completed(int qty) throws Exception {
        String id = released(qty);
        assertOk(report(id, "OP10", String.valueOf(qty)), 201);
        assertOk(report(id, "OP20", String.valueOf(qty)), 201);
        assertThat(status(id)).isEqualTo("COMPLETED");
        return id;
    }

    private String status(String id) { return jdbc.queryForObject("select status from work_order where id = ?", String.class, id); }
    private String taskStatus(String id, String opNo) {
        return jdbc.queryForObject("select status from operation_task where order_id = ? and op_no = ?", String.class, id, opNo);
    }
    private List<String> events(String id) {
        return jdbc.queryForList("select event_type from outbox_event where aggregate_id = ? and event_type like 'WorkOrder%' order by occurred_at, event_type", String.class, id);
    }
    private JsonNode json(MvcResult r) throws Exception { return mapper.readTree(r.getResponse().getContentAsString(StandardCharsets.UTF_8)); }
    private static void assertOk(MvcResult r, int status) throws Exception {
        assertThat(r.getResponse().getStatus()).as(r.getResponse().getContentAsString(StandardCharsets.UTF_8)).isEqualTo(status);
    }
    private static void assertProblem(MvcResult r, int status, String type) throws Exception {
        assertOk(r, status);
        assertThat(r.getResponse().getContentAsString(StandardCharsets.UTF_8)).contains("\"type\":\"" + type + "\"");
    }

    // ------------------------------------------------------------------ 关闭

    /** COMPLETED → CLOSED：逐个状态查询未结案 NCR，发布 WorkOrderClosed，写审计；同键重放不再查询质量服务。 */
    @Test void closeCompletedOrder() throws Exception {
        String id = completed(10);
        String key = "close-" + id;
        MvcResult r = call(writer(), "/workorders/" + id + "/close", "{\"note\":\"质检已结案\"}", key);
        assertOk(r, 200);
        assertThat(json(r).at("/data/status").asText()).isEqualTo("CLOSED");
        assertThat(json(r).at("/data/previousStatus").asText()).isEqualTo("COMPLETED");
        assertThat(status(id)).isEqualTo("CLOSED");
        assertThat(events(id)).contains("WorkOrderClosed");
        assertThat(jdbc.queryForObject("select tag from outbox_event where aggregate_id = ? and event_type = 'WorkOrderClosed'", String.class, id)).isEqualTo("WorkOrderClosed");
        assertThat(jdbc.queryForObject("select count(*) from work_order_status_log where order_id = ? and to_status = 'CLOSED' and reason = 'CLOSE'", Integer.class, id)).isEqualTo(1);
        for (String s : WorkOrderClosure.OPEN_NCR_STATUSES) verify(quality).ncrs(id, s, 0, 1);

        clearInvocations(quality);
        when(quality.ncrs(anyString(), anyString(), anyInt(), anyInt())).thenThrow(new IllegalStateException("down"));
        MvcResult replay = call(writer(), "/workorders/" + id + "/close", "{\"note\":\"质检已结案\"}", key);
        assertOk(replay, 200);
        assertThat(replay.getResponse().getHeader("Idempotent-Replayed")).isEqualTo("true");
        verifyNoInteractions(quality);
        // 换键：已关闭 409 workorder-closed（质量服务恢复后）
        reset(quality); noNcr();
        assertProblem(call("/workorders/" + id + "/close", null), 409, "workorder-closed");
        // 关闭后报工、取消均拒绝
        assertProblem(report(id, "OP20", "1"), 409, "workorder-closed");
        assertProblem(call("/workorders/" + id + "/cancel", null), 409, "workorder-closed");
    }

    /** 存在未结案 NCR：409 open-ncr-exists，data.openNcrCount 为各状态之和，工单仍为 COMPLETED。 */
    @Test void closeRefusedWithOpenNcr() throws Exception {
        String id = completed(5);
        when(quality.ncrs(eq(id), eq("UNDER_REVIEW"), anyInt(), anyInt())).thenReturn(new QualityClient.NcrPage(List.of(), 0, 1, 2));
        when(quality.ncrs(eq(id), eq("DISPOSED"), anyInt(), anyInt())).thenReturn(new QualityClient.NcrPage(List.of(), 0, 1, 1));
        MvcResult r = call("/workorders/" + id + "/close", null);
        assertProblem(r, 409, "open-ncr-exists");
        assertThat(json(r).at("/data/openNcrCount").asInt()).isEqualTo(3);
        assertThat(status(id)).isEqualTo("COMPLETED");
        assertThat(events(id)).doesNotContain("WorkOrderClosed");
    }

    /** 质量服务不可用（异常或空响应）：503 quality-service-unavailable，不放行；403 原样返回 access-denied。 */
    @Test void closeRefusedWhenQualityUnavailable() throws Exception {
        String id = completed(5);
        when(quality.ncrs(anyString(), anyString(), anyInt(), anyInt())).thenThrow(new feign.RetryableException(503, "timeout", feign.Request.HttpMethod.GET, (Long) null,
            feign.Request.create(feign.Request.HttpMethod.GET, "/quality/ncrs", java.util.Map.of(), null, StandardCharsets.UTF_8, null)));
        assertProblem(call("/workorders/" + id + "/close", null), 503, "quality-service-unavailable");
        assertThat(status(id)).isEqualTo("COMPLETED");

        reset(quality);
        when(quality.ncrs(anyString(), anyString(), anyInt(), anyInt())).thenReturn(null);
        assertProblem(call("/workorders/" + id + "/close", null), 503, "quality-service-unavailable");

        reset(quality);
        when(quality.ncrs(anyString(), anyString(), anyInt(), anyInt())).thenThrow(mock(FeignException.Forbidden.class));
        assertProblem(call("/workorders/" + id + "/close", null), 403, "access-denied");

        reset(quality);
        when(quality.ncrs(anyString(), anyString(), anyInt(), anyInt())).thenThrow(WorkOrderProblemType.QUALITY_SERVICE_UNAVAILABLE.exception("熔断"));
        assertProblem(call("/workorders/" + id + "/close", null), 503, "quality-service-unavailable");
        assertThat(status(id)).isEqualTo("COMPLETED");
    }

    /** 非 COMPLETED 不能关闭；已取消 409 workorder-cancelled；缺幂等键 400；只读用户 403。 */
    @Test void closeStateAndPermissionRules() throws Exception {
        String released = released(5);
        assertProblem(call("/workorders/" + released + "/close", null), 409, "workorder-state-conflict");
        String cancelled = ReleaseFixtures.createOrder(jdbc, 5);
        assertOk(call("/workorders/" + cancelled + "/cancel", null), 200);
        assertProblem(call("/workorders/" + cancelled + "/close", null), 409, "workorder-cancelled");
        assertProblem(call(writer(), "/workorders/" + released + "/close", null, null), 400, "idempotency-key-required");
        assertOk(call(reader(), "/workorders/" + released + "/close", null, "k-" + released), 403);
        assertProblem(call("/workorders/missing-order/close", null), 404, "workorder-not-found");
    }

    // ------------------------------------------------------------------ 撤回下达

    /** RELEASED → CREATED：删除下达快照、预留登记释放任务（CALLER_REQUEST）、发布事件；之后可换键重新下达，旧键 409。 */
    @Test void withdrawReleaseAndReRelease() throws Exception {
        String id = released(10);
        String reservation = jdbc.queryForObject("select reservation_id from wo_material_reservation where order_id = ? and status = 'ACTIVE'", String.class, id);
        String key = "wd-" + id;
        MvcResult r = call(writer(), "/workorders/" + id + "/withdraw-release", "{\"note\":\"BOM 有误\"}", key);
        assertOk(r, 200);
        assertThat(json(r).at("/data/status").asText()).isEqualTo("CREATED");
        assertThat(json(r).at("/data/reservationId").asText()).isEqualTo(reservation);
        assertThat(json(r).at("/data/releaseStatus").asText()).isEqualTo("PENDING");
        assertThat(status(id)).isEqualTo("CREATED");
        for (String t : List.of("operation_task", "wo_operation_progress", "wo_bom_snapshot", "work_order_lifecycle"))
            assertThat(jdbc.queryForObject("select count(*) from " + t + " where order_id = ?", Integer.class, id)).as(t).isZero();
        assertThat(jdbc.queryForObject("select status from wo_material_reservation where reservation_id = ?", String.class, reservation)).isEqualTo("RELEASING");
        assertThat(jdbc.queryForObject("select reason from wo_reservation_release_task where reservation_id = ?", String.class, reservation)).isEqualTo("CALLER_REQUEST");
        assertThat(jdbc.queryForObject("select idempotency_key from wo_reservation_release_task where reservation_id = ?", String.class, reservation))
            .isEqualTo(WorkOrderClosure.withdrawKey(id, reservation));
        assertThat(events(id)).contains("WorkOrderReleaseWithdrawn");
        // 同键重放
        MvcResult replay = call(writer(), "/workorders/" + id + "/withdraw-release", "{\"note\":\"BOM 有误\"}", key);
        assertOk(replay, 200);
        assertThat(replay.getResponse().getHeader("Idempotent-Replayed")).isEqualTo("true");
        // 换键：已是 CREATED → 409
        assertProblem(call("/workorders/" + id + "/withdraw-release", null), 409, "workorder-state-conflict");

        // 旧下达键不能再用
        assertProblem(call(writer(), "/workorders/" + id + "/release", null, "rel-" + id), 409, "workorder-state-conflict");
        // 新键重新下达：任务重建、预留序号顺延
        assertOk(call(writer(), "/workorders/" + id + "/release", null, "rel2-" + id), 200);
        assertThat(status(id)).isEqualTo("RELEASED");
        assertThat(taskStatus(id, "OP10")).isEqualTo("READY");
        assertThat(jdbc.queryForObject("select max(seq) from wo_material_reservation where order_id = ?", Integer.class, id)).isEqualTo(2);
    }

    /** 有报工（含已冲销）、有开工任务、状态不是 RELEASED、已取消：拒绝撤回。 */
    @Test void withdrawRefusedAfterWorkStarted() throws Exception {
        String reported = released(10);
        assertOk(report(reported, "OP10", "1"), 201);
        assertProblem(call("/workorders/" + reported + "/withdraw-release", null), 409, "workorder-state-conflict");

        String started = released(10);
        jdbc.update("update operation_task set started_at = current_timestamp where order_id = ? and op_no = 'OP10'", started);
        assertProblem(call("/workorders/" + started + "/withdraw-release", null), 409, "workorder-state-conflict");
        assertThat(status(started)).isEqualTo("RELEASED");

        String created = ReleaseFixtures.createOrder(jdbc, 5);
        assertProblem(call("/workorders/" + created + "/withdraw-release", null), 409, "workorder-state-conflict");
        assertOk(call("/workorders/" + created + "/cancel", null), 200);
        assertProblem(call("/workorders/" + created + "/withdraw-release", null), 409, "workorder-cancelled");

        String closed = completed(2);
        assertOk(call("/workorders/" + closed + "/close", null), 200);
        assertProblem(call("/workorders/" + closed + "/withdraw-release", null), 409, "workorder-closed");
    }

    /** 没有有效预留（例如已过期）时也可以撤回，不登记释放任务。 */
    @Test void withdrawWithoutActiveReservation() throws Exception {
        String id = released(10);
        jdbc.update("update wo_material_reservation set status = 'EXPIRED' where order_id = ?", id);
        MvcResult r = call("/workorders/" + id + "/withdraw-release", null);
        assertOk(r, 200);
        assertThat(json(r).at("/data/reservationId").isNull()).isTrue();
        assertThat(jdbc.queryForObject("select count(*) from wo_reservation_release_task where order_id = ?", Integer.class, id)).isZero();
    }

    // ------------------------------------------------------------------ 列表 / 引用 / 进度 / #69

    /** 分页、状态过滤、关键字（工单号或产品，% 与 _ 按字面量）、参数校验。 */
    @Test void listPagingAndFilters() throws Exception {
        String a = released(1);
        String b = ReleaseFixtures.createOrder(jdbc, 1);
        String c = ReleaseFixtures.createOrder(jdbc, 1);
        jdbc.update("update work_order set product = 'WIDGET_50%' where id = ?", c);

        JsonNode all = json(mvc.perform(get("/workorders").with(reader())).andReturn()).get("data");
        assertThat(all.get("total").asLong()).isEqualTo(3);
        assertThat(all.get("page").asInt()).isZero();
        assertThat(all.get("size").asInt()).isEqualTo(20);

        JsonNode page = json(mvc.perform(get("/workorders").param("page", "1").param("size", "2").with(reader())).andReturn()).get("data");
        assertThat(page.get("items")).hasSize(1);
        assertThat(page.get("total").asLong()).isEqualTo(3);

        JsonNode released = json(mvc.perform(get("/workorders").param("status", "RELEASED").with(reader())).andReturn()).get("data");
        assertThat(released.get("total").asLong()).isEqualTo(1);
        assertThat(released.at("/items/0/id").asText()).isEqualTo(a);

        JsonNode byId = json(mvc.perform(get("/workorders").param("keyword", b.substring(0, 13)).with(reader())).andReturn()).get("data");
        assertThat(byId.at("/items/0/id").asText()).isEqualTo(b);
        JsonNode literal = json(mvc.perform(get("/workorders").param("keyword", "_50%").with(reader())).andReturn()).get("data");
        assertThat(literal.get("total").asLong()).isEqualTo(1);
        assertThat(literal.at("/items/0/id").asText()).isEqualTo(c);
        JsonNode none = json(mvc.perform(get("/workorders").param("keyword", "GR60%X").with(reader())).andReturn()).get("data");
        assertThat(none.get("total").asLong()).isZero();

        assertProblem(mvc.perform(get("/workorders").param("size", "0").with(reader())).andReturn(), 400, "validation-error");
        assertProblem(mvc.perform(get("/workorders").param("size", "101").with(reader())).andReturn(), 400, "validation-error");
        assertProblem(mvc.perform(get("/workorders").param("page", "-1").with(reader())).andReturn(), 400, "validation-error");
        assertProblem(mvc.perform(get("/workorders").param("status", "DONE").with(reader())).andReturn(), 400, "validation-error");
    }

    /** 主数据引用检查：只统计执行中的工单；masterdata:write 可调用；参数必须二选一；无权限 403。 */
    @Test void referencesForMasterdata() throws Exception {
        String active = released(5);
        jdbc.update("update operation_task set station_code = 'ST-CNC-01' where order_id = ? and op_no = 'OP10'", active);
        String done = completed(1);
        RequestPostProcessor engineer = user("eng-1", "masterdata:read", "masterdata:write");

        JsonNode wc = json(mvc.perform(get("/workorders/references").param("workCenter", "WC-CNC").with(engineer)).andReturn()).get("data");
        assertThat(wc.get("count").asInt()).isEqualTo(1);
        assertThat(wc.at("/workOrderNos/0").asText()).isEqualTo(active).isNotEqualTo(done);
        JsonNode st = json(mvc.perform(get("/workorders/references").param("station", "ST-CNC-01").with(reader())).andReturn()).get("data");
        assertThat(st.get("count").asInt()).isEqualTo(1);
        JsonNode free = json(mvc.perform(get("/workorders/references").param("station", "ST-CNC-02").with(reader())).andReturn()).get("data");
        assertThat(free.get("count").asInt()).isZero();

        assertProblem(mvc.perform(get("/workorders/references").with(reader())).andReturn(), 400, "validation-error");
        assertProblem(mvc.perform(get("/workorders/references").param("station", "A").param("workCenter", "B").with(reader())).andReturn(), 400, "validation-error");
        assertOk(mvc.perform(get("/workorders/references").param("station", "A").with(user("x", "masterdata:read"))).andReturn(), 403);
    }

    /** 版本引用检查：当前下达快照（含子件来源版本）与已撤回下达都算；未引用为 0；参数校验与权限。 */
    @Test void versionReferencesForMasterdata() throws Exception {
        RequestPostProcessor engineer = user("eng-1", "masterdata:read", "masterdata:write");
        String p = ReleaseFixtures.PRODUCT;
        assertThat(versionRefs(engineer, "BOM", p, "A01").get("count").asInt()).isZero();
        String a = released(2);
        assertThat(versionRefs(engineer, "BOM", p, "A01").at("/workOrderNos/0").asText()).isEqualTo(a);
        assertThat(versionRefs(reader(), "BOM", "SA-HOUSING", "A01").get("count").asInt()).isEqualTo(1);
        assertThat(versionRefs(reader(), "ROUTING", p, "R01").get("count").asInt()).isEqualTo(1);
        assertThat(versionRefs(reader(), "ROUTING", p, "R02").get("count").asInt()).isZero();
        assertOk(call(writer(), "/workorders/" + a + "/withdraw-release", "{}", "wd-v-" + a), 200);
        assertThat(jdbc.queryForObject("select count(*) from work_order_lifecycle where order_id = ?", Integer.class, a)).isZero();
        assertThat(versionRefs(reader(), "BOM", p, "A01").get("count").asInt()).isEqualTo(1);
        assertThat(versionRefs(reader(), "ROUTING", p, "R01").at("/workOrderNos/0").asText()).isEqualTo(a);

        assertProblem(mvc.perform(get("/workorders/version-references").param("type", "BOM").param("product", p).with(reader())).andReturn(), 400, "validation-error");
        assertProblem(mvc.perform(get("/workorders/version-references").param("type", "X").param("product", p).param("version", "A01").with(reader())).andReturn(), 400, "validation-error");
        assertProblem(mvc.perform(get("/workorders/version-references").param("product", " ").param("version", "A01").with(reader())).andReturn(), 400, "validation-error");
        assertOk(mvc.perform(get("/workorders/version-references").param("type", "BOM").param("product", p).param("version", "A01")
            .with(user("x", "masterdata:read"))).andReturn(), 403);
    }

    private JsonNode versionRefs(RequestPostProcessor who, String type, String product, String version) throws Exception {
        MvcResult r = mvc.perform(get("/workorders/version-references").param("type", type).param("product", product).param("version", version).with(who)).andReturn();
        assertOk(r, 200);
        return json(r).get("data");
    }

    /** 进度附带工序名称与工作中心（取自下达时的工艺快照）。 */
    @Test void progressIncludesOperationNameAndWorkCenter() throws Exception {
        String id = released(5);
        JsonNode ops = json(mvc.perform(get("/workorders/" + id + "/progress").with(reader())).andReturn()).at("/data/operations");
        for (JsonNode op : ops) {
            String name = jdbc.queryForObject("select name from operation_task where order_id = ? and op_no = ?", String.class, id, op.get("operationNo").asText());
            assertThat(op.get("operationName").asText()).isEqualTo(name);
        }
        assertThat(ops.at("/0/workCenterCode").asText()).isEqualTo("WC-CNC");
        assertThat(ops.at("/1/workCenterCode").asText()).isEqualTo("WC-ASM");
    }

    /** #69：首道报工使下游 READY，冲销后上游合格数回到 0 且下游无报工 → 下游退回 PENDING（不发事件）；部分冲销保持 READY。 */
    @Test void reversalReturnsDownstreamReadyToPending() throws Exception {
        String id = released(10);
        String first = json(report(id, "OP10", "3")).at("/data/report/reportId").asText();
        String second = json(report(id, "OP10", "2")).at("/data/report/reportId").asText();
        assertThat(taskStatus(id, "OP20")).isEqualTo("READY");
        assertOk(call("/workorders/" + id + "/reports/" + first + "/reversal", "{\"reason\":\"误报\"}"), 201);
        assertThat(taskStatus(id, "OP20")).as("上游仍有合格数流入").isEqualTo("READY");
        long before = jdbc.queryForObject("select count(*) from outbox_event where aggregate_id = ?", Long.class, id);
        assertOk(call("/workorders/" + id + "/reports/" + second + "/reversal", "{\"reason\":\"误报\"}"), 201);
        assertThat(taskStatus(id, "OP20")).isEqualTo("PENDING");
        assertThat(jdbc.queryForObject("select count(*) from operation_task_log where order_id = ? and op_no = 'OP20' and to_status = 'PENDING' and reason = 'REPORT_REVERSED'",
            Integer.class, id)).isEqualTo(1);
        assertThat(jdbc.queryForObject("select count(*) from outbox_event where aggregate_id = ?", Long.class, id) - before)
            .as("退回 PENDING 不对外发事件（无不良数，冲销本身也不产生事件）").isZero();
        // 再次报工重新流入 → READY
        assertOk(report(id, "OP10", "1"), 201);
        assertThat(taskStatus(id, "OP20")).isEqualTo("READY");
    }
}
