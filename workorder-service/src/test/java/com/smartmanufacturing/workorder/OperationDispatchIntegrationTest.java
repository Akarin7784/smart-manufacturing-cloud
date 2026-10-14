package com.smartmanufacturing.workorder;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.smartmanufacturing.api.workorder.HoldReason;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
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
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * M1 工序派工与任务状态（H2，经 HTTP 入口）：派工 / 改派 / 取消派工 / 开工 / 暂停 / 恢复、报工自动开工、工时校验与反冲、
 * DONE 冲销回 PAUSED、冻结暂停与取消级联、权限（新权限、PLANNER、workorder:write 兼容）与幂等。
 * 并发改派“只有一个成功”的真实行锁语义见 OperationDispatchMySqlIT（需要 Docker，只在 CI 运行）。
 * 工艺快照：OP10（WC-CNC，工位 ST-CNC-01/02）→ OP20（WC-ASM，工位 ST-ASM-01），计划数 10。
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class OperationDispatchIntegrationTest {
    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired ObjectMapper mapper;
    @Autowired WorkOrderReleaseService release;
    @Autowired LocalMaterialReservation stub;
    @MockitoBean MasterdataClient masterdata;
    @MockitoBean DeviceClient devices;
    @MockitoBean org.springframework.security.oauth2.jwt.JwtDecoder decoder;

    @BeforeEach void setUp() { ReleaseFixtures.clear(jdbc); ReleaseFixtures.stubMasterdata(masterdata); stub.reset(); }

    // ------------------------------------------------------------------ 夹具

    private static RequestPostProcessor user(String subject, String... authorities) {
        return jwt().jwt(j -> j.subject(subject)).authorities(Arrays.stream(authorities).map(a -> (org.springframework.security.core.GrantedAuthority) new SimpleGrantedAuthority(a)).toList());
    }
    /** 计划员：与 auth Role.PLANNER 的权限一致。 */
    private static RequestPostProcessor planner() { return user("planner-1", "device:read", "workorder:read", "masterdata:read", "execution:dispatch"); }
    /** 新权限下的操作工（不含 workorder:write，用来证明 execution:report 本身足够）。 */
    private static RequestPostProcessor worker(String subject) { return user(subject, "workorder:read", "execution:report"); }
    private static RequestPostProcessor legacyWriter() { return user("legacy-1", "workorder:read", "workorder:write"); }

    private String released(int qty) {
        String id = ReleaseFixtures.createOrder(jdbc, qty);
        release.release(id, "rel-" + id, "planner");
        return id;
    }

    private MvcResult call(RequestPostProcessor who, String path, String body) throws Exception {
        return call(who, path, body, UUID.randomUUID().toString());
    }

    private MvcResult call(RequestPostProcessor who, String path, String body, String key) throws Exception {
        var req = post(path).with(who).contentType(MediaType.APPLICATION_JSON).content(body);
        if (key != null) req.header("Idempotency-Key", key);
        return mvc.perform(req).andReturn();
    }

    private String op(String id, String opNo, String action) { return "/workorders/" + id + "/operations/" + opNo + "/" + action; }

    private MvcResult dispatch(String id, String opNo, String station, String operator) throws Exception {
        return call(planner(), op(id, opNo, "dispatch"), "{\"stationCode\":\"" + station + "\"" + (operator == null ? "" : ",\"operator\":\"" + operator + "\"") + "}");
    }

    private MvcResult action(RequestPostProcessor who, String id, String opNo, String action, String reason) throws Exception {
        return call(who, op(id, opNo, action), reason == null ? "{}" : "{\"reason\":\"" + reason + "\"}");
    }

    private MvcResult report(RequestPostProcessor who, String id, String opNo, String good, String hours) throws Exception {
        String labor = hours == null ? "" : ",\"laborHours\":" + hours;
        return call(who, op(id, opNo, "reports"), "{\"goodQty\":\"" + good + "\",\"defectQty\":\"0\",\"scrapQty\":\"0\",\"uom\":\"PCS\"" + labor + "}");
    }

    private MvcResult reverse(String id, String reportId) throws Exception {
        return call(worker("op-1"), "/workorders/" + id + "/reports/" + reportId + "/reversal", "{\"reason\":\"误报\"}");
    }

    private JsonNode json(MvcResult r) throws Exception { return mapper.readTree(r.getResponse().getContentAsString(StandardCharsets.UTF_8)); }

    private static void assertProblem(MvcResult r, int status, String type) throws Exception {
        assertThat(r.getResponse().getStatus()).as(r.getResponse().getContentAsString(StandardCharsets.UTF_8)).isEqualTo(status);
        assertThat(r.getResponse().getContentAsString(StandardCharsets.UTF_8)).contains("\"type\":\"" + type + "\"");
    }

    private static void assertOk(MvcResult r, int status) throws Exception {
        assertThat(r.getResponse().getStatus()).as(r.getResponse().getContentAsString(StandardCharsets.UTF_8)).isEqualTo(status);
    }

    private String taskStatus(String id, String opNo) {
        return jdbc.queryForObject("select status from operation_task where order_id = ? and op_no = ?", String.class, id, opNo);
    }

    private long taskVersion(String id, String opNo) {
        return jdbc.queryForObject("select version from operation_task where order_id = ? and op_no = ?", Long.class, id, opNo);
    }

    private List<String> events(String id) {
        return jdbc.queryForList("select event_type from outbox_event where aggregate_id = ? order by occurred_at, event_type", String.class, id);
    }

    private JsonNode progressOp(String id, int index) throws Exception {
        MvcResult r = mvc.perform(get("/workorders/" + id + "/progress").with(planner())).andReturn();
        assertOk(r, 200);
        return json(r).at("/data/operations/" + index);
    }

    // ------------------------------------------------------------------ 主流程

    /** 派工 → 开工 → 报工到上限：OP10 DONE、OP20 READY；进度带派工信息与工时；日志和 Operation* 事件齐全。 */
    @Test void dispatchStartReportCompletesTask() throws Exception {
        String id = released(10);
        MvcResult d = dispatch(id, "OP10", "ST-CNC-01", "op-a");
        assertOk(d, 200);
        assertThat(json(d).at("/data/stationCode").asText()).isEqualTo("ST-CNC-01");
        assertThat(json(d).at("/data/status").asText()).isEqualTo("READY");
        assertOk(action(worker("op-a"), id, "OP10", "start", null), 200);
        assertThat(taskStatus(id, "OP10")).isEqualTo("RUNNING");
        assertOk(report(worker("op-a"), id, "OP10", "10", "\"2.50\""), 201);
        assertThat(taskStatus(id, "OP10")).isEqualTo("DONE");
        assertThat(taskStatus(id, "OP20")).isEqualTo("READY");

        JsonNode p = progressOp(id, 0);
        assertThat(p.get("taskStatus").asText()).isEqualTo("DONE");
        assertThat(p.get("stationCode").asText()).isEqualTo("ST-CNC-01");
        assertThat(p.get("operator").asText()).isEqualTo("op-a");
        assertThat(p.get("laborHours").asText()).isEqualTo("2.50");
        assertThat(progressOp(id, 1).get("stationCode").isNull()).isTrue();
        assertThat(events(id)).contains("OperationDispatched", "OperationStarted", "OperationCompleted");
        assertThat(jdbc.queryForList("select action from operation_task_log where order_id = ? and op_no = 'OP10' order by created_at", String.class, id))
            .containsSubsequence("DISPATCH", "START", "DONE");
    }

    /** 未派工的老任务仍可报工：READY 自动开工为 RUNNING，报工记录工位为空（PM 确认：M1 报工不强制派工）。 */
    @Test void undispatchedLegacyTaskCanReportAndAutoStarts() throws Exception {
        String id = released(10);
        MvcResult r = report(legacyWriter(), id, "OP10", "3", "\"1\"");
        assertOk(r, 201);
        assertThat(taskStatus(id, "OP10")).isEqualTo("RUNNING");
        assertThat(json(r).at("/data/report/stationCode").isNull()).isTrue();
        assertThat(json(r).at("/data/report/laborHours").asText()).isEqualTo("1.00");
        assertThat(jdbc.queryForObject("select reason from operation_task_log where order_id = ? and action = 'START'", String.class, id))
            .isEqualTo("AUTO_START_BY_REPORT");
        // 下道有了流入：PENDING → READY；下道报工同样自动开工
        assertThat(taskStatus(id, "OP20")).isEqualTo("READY");
        assertOk(report(legacyWriter(), id, "OP20", "2", "\"0.5\""), 201);
        assertThat(taskStatus(id, "OP20")).isEqualTo("RUNNING");
    }

    /** 已派工的 READY 任务直接报工同样自动开工。 */
    @Test void reportOnDispatchedReadyTaskAutoStarts() throws Exception {
        String id = released(10);
        dispatch(id, "OP10", "ST-CNC-01", null);
        assertOk(report(worker("op-1"), id, "OP10", "4", "\"1.00\""), 201);
        assertThat(taskStatus(id, "OP10")).isEqualTo("RUNNING");
        assertThat(json(report(worker("op-1"), id, "OP10", "1", "\"0\"")).at("/data/report/stationCode").asText()).isEqualTo("ST-CNC-01");
    }

    /** 未派工时显式开工 / 暂停 / 恢复返回 409 task-not-dispatched；改派也要求先派工。 */
    @Test void startPauseResumeRequireDispatch() throws Exception {
        String id = released(10);
        assertProblem(action(worker("op-1"), id, "OP10", "start", null), 409, "task-not-dispatched");
        assertProblem(action(worker("op-1"), id, "OP10", "pause", "换刀"), 409, "task-not-dispatched");
        assertProblem(action(worker("op-1"), id, "OP10", "resume", null), 409, "task-not-dispatched");
        assertProblem(call(planner(), op(id, "OP10", "reassign"), "{\"stationCode\":\"ST-CNC-01\",\"expectedVersion\":0}"), 409, "task-not-dispatched");
        assertProblem(action(planner(), id, "OP10", "cancel-dispatch", "取消"), 409, "task-not-dispatched");
    }

    /** 工位不存在、不属于该工序工作中心、工作中心在主数据中不存在，均返回 422 station-not-found。 */
    @Test void unknownStationIsRejected() throws Exception {
        String id = released(10);
        assertProblem(dispatch(id, "OP10", "ST-NOPE", null), 422, "station-not-found");
        assertProblem(dispatch(id, "OP10", "ST-ASM-01", null), 422, "station-not-found");
        jdbc.update("update operation_task set work_center_code = 'WC-GONE' where order_id = ? and op_no = 'OP20'", id);
        assertProblem(dispatch(id, "OP20", "ST-ASM-01", null), 422, "station-not-found");
        assertThat(jdbc.queryForObject("select count(*) from operation_task where order_id = ? and station_code is not null", Integer.class, id)).isZero();
    }

    /** 不允许的任务状态：重复派工、PAUSED 时报工、RUNNING 时开工、READY 时暂停 / 恢复，均为 409 task-state-conflict。 */
    @Test void illegalTransitionsAreTaskStateConflicts() throws Exception {
        String id = released(10);
        dispatch(id, "OP10", "ST-CNC-01", null);
        assertProblem(dispatch(id, "OP10", "ST-CNC-02", null), 409, "task-state-conflict");
        assertProblem(action(worker("op-1"), id, "OP10", "pause", "x"), 409, "task-state-conflict");
        assertProblem(action(worker("op-1"), id, "OP10", "resume", null), 409, "task-state-conflict");
        assertOk(action(worker("op-1"), id, "OP10", "start", null), 200);
        assertProblem(action(worker("op-1"), id, "OP10", "start", null), 409, "task-state-conflict");
        assertProblem(action(worker("op-1"), id, "OP10", "pause", null), 400, "validation-error");
        assertOk(action(worker("op-1"), id, "OP10", "pause", "换刀"), 200);
        assertProblem(report(worker("op-1"), id, "OP10", "1", "\"1\""), 409, "task-state-conflict");
        assertOk(action(worker("op-1"), id, "OP10", "resume", null), 200);
        assertThat(events(id)).contains("OperationPaused", "OperationResumed");
    }

    // ------------------------------------------------------------------ 改派

    /** RUNNING 时改派返回 409 task-state-conflict，任务不变。 */
    @Test void reassignWhileRunningIsConflict() throws Exception {
        String id = released(10);
        dispatch(id, "OP10", "ST-CNC-01", null);
        action(worker("op-1"), id, "OP10", "start", null);
        long v = taskVersion(id, "OP10");
        MvcResult r = call(planner(), op(id, "OP10", "reassign"), "{\"stationCode\":\"ST-CNC-02\",\"expectedVersion\":" + v + "}");
        assertProblem(r, 409, "task-state-conflict");
        assertThat(jdbc.queryForObject("select station_code from operation_task where order_id = ? and op_no = 'OP10'", String.class, id)).isEqualTo("ST-CNC-01");
    }

    /** 报工后暂停再改派：任务换到新工位、新操作工，已有报工记录的工位和操作人保持不变。 */
    @Test void reassignKeepsExistingReports() throws Exception {
        String id = released(10);
        dispatch(id, "OP10", "ST-CNC-01", "op-a");
        assertOk(action(worker("op-a"), id, "OP10", "start", null), 200);
        assertOk(report(worker("op-a"), id, "OP10", "3", "\"1.50\""), 201);
        assertOk(action(worker("op-a"), id, "OP10", "pause", "交班"), 200);
        long v = taskVersion(id, "OP10");
        MvcResult r = call(planner(), op(id, "OP10", "reassign"), "{\"stationCode\":\"ST-CNC-02\",\"operator\":\"op-b\",\"expectedVersion\":" + v + "}");
        assertOk(r, 200);
        assertThat(json(r).at("/data/stationCode").asText()).isEqualTo("ST-CNC-02");
        assertThat(json(r).at("/data/operator").asText()).isEqualTo("op-b");
        assertThat(json(r).at("/data/status").asText()).isEqualTo("PAUSED");
        var rows = jdbc.queryForList("select station_code, operator, labor_hours from wo_operation_report where order_id = ?", id);
        assertThat(rows).hasSize(1);
        assertThat(rows.get(0).get("STATION_CODE")).isEqualTo("ST-CNC-01");
        assertThat(rows.get(0).get("OPERATOR")).isEqualTo("op-a");
        assertThat(events(id)).filteredOn("OperationDispatched"::equals).hasSize(2);
        // 改派后新操作工恢复并继续报工，新记录落在新工位
        assertOk(action(worker("op-b"), id, "OP10", "resume", null), 200);
        assertThat(json(report(worker("op-b"), id, "OP10", "1", "\"0.25\"")).at("/data/report/stationCode").asText()).isEqualTo("ST-CNC-02");
    }

    /** expectedVersion 过期返回 409 concurrent-modification；缺失返回 400 validation-error。 */
    @Test void staleExpectedVersionIsRejected() throws Exception {
        String id = released(10);
        dispatch(id, "OP10", "ST-CNC-01", null);
        long v = taskVersion(id, "OP10");
        assertProblem(call(planner(), op(id, "OP10", "reassign"), "{\"stationCode\":\"ST-CNC-02\",\"expectedVersion\":" + (v - 1) + "}"), 409, "concurrent-modification");
        assertProblem(call(planner(), op(id, "OP10", "reassign"), "{\"stationCode\":\"ST-CNC-02\"}"), 400, "validation-error");
        assertOk(call(planner(), op(id, "OP10", "reassign"), "{\"stationCode\":\"ST-CNC-02\",\"expectedVersion\":" + v + "}"), 200);
        // 同一个旧版本再次改派（换键）：版本已递增，必然冲突
        assertProblem(call(planner(), op(id, "OP10", "reassign"), "{\"stationCode\":\"ST-CNC-01\",\"expectedVersion\":" + v + "}"), 409, "concurrent-modification");
    }

    /** 取消派工：需要 reason；已有报工时拒绝；成功后任务回到未派工。 */
    @Test void cancelDispatch() throws Exception {
        String id = released(10);
        dispatch(id, "OP20", "ST-ASM-01", null);
        assertProblem(action(planner(), id, "OP20", "cancel-dispatch", null), 400, "validation-error");
        assertOk(action(planner(), id, "OP20", "cancel-dispatch", "计划调整"), 200);
        assertThat(jdbc.queryForObject("select station_code from operation_task where order_id = ? and op_no = 'OP20'", String.class, id)).isNull();
        assertThat(events(id)).contains("OperationDispatchCancelled");
        dispatch(id, "OP10", "ST-CNC-01", null);
        report(worker("op-1"), id, "OP10", "1", "\"0\"");
        assertProblem(action(planner(), id, "OP10", "cancel-dispatch", "计划调整"), 409, "task-state-conflict");
    }

    /** 指派了 op-a，op-b 也可以开工和报工（PM 确认：M1 只记录不限制）。 */
    @Test void otherOperatorCanStartAndReport() throws Exception {
        String id = released(10);
        dispatch(id, "OP10", "ST-CNC-01", "op-a");
        assertOk(action(worker("op-b"), id, "OP10", "start", null), 200);
        MvcResult r = report(worker("op-b"), id, "OP10", "2", "\"1\"");
        assertOk(r, 201);
        assertThat(json(r).at("/data/report/operator").asText()).isEqualTo("op-b");
        assertThat(jdbc.queryForObject("select operator from operation_task where order_id = ? and op_no = 'OP10'", String.class, id)).isEqualTo("op-a");
    }

    // ------------------------------------------------------------------ 工时

    /** laborHours 缺失、负数、超过 2 位小数、非数字均为 400 validation-error，且不落任何报工。 */
    @Test void laborHoursValidation() throws Exception {
        String id = released(10);
        assertProblem(report(worker("op-1"), id, "OP10", "1", null), 400, "validation-error");
        assertProblem(report(worker("op-1"), id, "OP10", "1", "\"-1\""), 400, "validation-error");
        assertProblem(report(worker("op-1"), id, "OP10", "1", "\"1.234\""), 400, "validation-error");
        assertProblem(report(worker("op-1"), id, "OP10", "1", "\"abc\""), 400, "validation-error");
        assertThat(jdbc.queryForObject("select count(*) from wo_operation_report where order_id = ?", Integer.class, id)).isZero();
        assertOk(report(worker("op-1"), id, "OP10", "1", "\"0\""), 201);
        assertOk(report(worker("op-1"), id, "OP10", "1", "\"12.5\""), 201);
        assertThat(progressOp(id, 0).get("laborHours").asText()).isEqualTo("12.50");
    }

    /** 冲销后累计工时回滚：冲销记录工时为负数，工序累计减去原工时。 */
    @Test void reversalRollsBackLaborHours() throws Exception {
        String id = released(10);
        String first = json(report(worker("op-1"), id, "OP10", "3", "\"1.25\"")).at("/data/report/reportId").asText();
        report(worker("op-1"), id, "OP10", "2", "\"0.50\"");
        assertThat(progressOp(id, 0).get("laborHours").asText()).isEqualTo("1.75");
        MvcResult rev = reverse(id, first);
        assertOk(rev, 201);
        assertThat(json(rev).at("/data/report/laborHours").asText()).isEqualTo("-1.25");
        assertThat(progressOp(id, 0).get("laborHours").asText()).isEqualTo("0.50");
        assertThat(jdbc.queryForObject("select sum(labor_hours) from wo_operation_report where order_id = ?", java.math.BigDecimal.class, id))
            .isEqualByComparingTo("0.50");
    }

    // ------------------------------------------------------------------ 冲销与任务状态

    /** DONE 的任务冲销后累计不足上限 → PAUSED（不回 RUNNING）；PAUSED 不能报工，补派工后显式 resume 才能继续。 */
    @Test void reversalOfDoneTaskPauses() throws Exception {
        String id = released(10);
        String rid = json(report(worker("op-1"), id, "OP10", "10", "\"3\"")).at("/data/report/reportId").asText();
        assertThat(taskStatus(id, "OP10")).isEqualTo("DONE");
        assertOk(reverse(id, rid), 201);
        assertThat(taskStatus(id, "OP10")).isEqualTo("PAUSED");
        assertThat(jdbc.queryForObject("select reason from operation_task_log where order_id = ? and to_status = 'PAUSED'", String.class, id))
            .isEqualTo("REPORT_REVERSED");
        assertProblem(report(worker("op-1"), id, "OP10", "1", "\"1\""), 409, "task-state-conflict");
        // 未派工的老数据：先补派工（PAUSED 允许），再 resume
        assertProblem(action(worker("op-1"), id, "OP10", "resume", null), 409, "task-not-dispatched");
        assertOk(dispatch(id, "OP10", "ST-CNC-01", null), 200);
        assertOk(action(worker("op-1"), id, "OP10", "resume", null), 200);
        assertOk(report(worker("op-1"), id, "OP10", "10", "\"3\""), 201);
        assertThat(taskStatus(id, "OP10")).isEqualTo("DONE");
    }

    /** 下游工序已报工时冲销上游照旧拒绝（409 downstream-reported），任务状态不变。 */
    @Test void reversalRejectedWhenDownstreamReported() throws Exception {
        String id = released(10);
        String rid = json(report(worker("op-1"), id, "OP10", "10", "\"3\"")).at("/data/report/reportId").asText();
        assertOk(report(worker("op-1"), id, "OP20", "5", "\"1\""), 201);
        assertProblem(reverse(id, rid), 409, "downstream-reported");
        assertThat(taskStatus(id, "OP10")).isEqualTo("DONE");
        // 下游达到上限且上游已完工 → 下游 DONE，工单自动完工
        assertOk(report(worker("op-1"), id, "OP20", "5", "\"1\""), 201);
        assertThat(taskStatus(id, "OP20")).isEqualTo("DONE");
        assertThat(jdbc.queryForObject("select status from work_order where id = ?", String.class, id)).isEqualTo("COMPLETED");
    }

    // ------------------------------------------------------------------ 工单联动

    /** 工单冻结：RUNNING 任务 → PAUSED；冻结中不能派工、恢复。 */
    @Test void holdPausesRunningTasks() throws Exception {
        String id = released(10);
        dispatch(id, "OP10", "ST-CNC-01", null);
        action(worker("op-1"), id, "OP10", "start", null);
        release.hold(id, HoldReason.MANUAL, null, "qc");
        assertThat(taskStatus(id, "OP10")).isEqualTo("PAUSED");
        assertThat(jdbc.queryForObject("select reason from operation_task_log where order_id = ? and to_status = 'PAUSED'", String.class, id)).isEqualTo("WORK_ORDER_HOLD");
        assertProblem(action(worker("op-1"), id, "OP10", "resume", null), 409, "workorder-state-conflict");
        assertProblem(dispatch(id, "OP20", "ST-ASM-01", null), 409, "workorder-state-conflict");
        release.resume(id, HoldReason.MANUAL, null, "qc");
        assertThat(taskStatus(id, "OP10")).isEqualTo("PAUSED");
        assertOk(action(worker("op-1"), id, "OP10", "resume", null), 200);
    }

    /** 工单取消：未完成的任务级联 CANCELLED 并发 OperationCancelled；取消后派工 409 workorder-cancelled。 */
    @Test void cancelCascadesToTasks() throws Exception {
        String id = released(10);
        dispatch(id, "OP10", "ST-CNC-01", null);
        action(worker("op-1"), id, "OP10", "start", null);
        assertOk(call(legacyWriter(), "/workorders/" + id + "/cancel", "{}"), 200);
        assertThat(jdbc.queryForList("select status from operation_task where order_id = ?", String.class, id)).containsOnly("CANCELLED");
        assertThat(events(id)).filteredOn("OperationCancelled"::equals).hasSize(2);
        assertProblem(dispatch(id, "OP20", "ST-ASM-01", null), 409, "workorder-cancelled");
    }

    /** 未下达工单派工 404 workorder-not-released；不存在的工单 / 工序 404。 */
    @Test void dispatchRequiresReleasedOrder() throws Exception {
        String created = ReleaseFixtures.createOrder(jdbc, 5);
        assertProblem(dispatch(created, "OP10", "ST-CNC-01", null), 404, "workorder-not-released");
        assertProblem(dispatch("NO-SUCH", "OP10", "ST-CNC-01", null), 404, "workorder-not-found");
        String id = released(10);
        assertProblem(dispatch(id, "OP99", "ST-CNC-01", null), 404, "operation-not-found");
        assertProblem(call(planner(), op(id, "OP10", "dispatch"), "{\"stationCode\":\"ST-CNC-01\",\"plannedStart\":\"2026-10-15T08:00:00Z\",\"plannedEnd\":\"2026-10-15T07:00:00Z\"}"), 400, "validation-error");
    }

    // ------------------------------------------------------------------ 幂等

    /** 缺少 Idempotency-Key 400；同键重放返回首次响应并带 Idempotent-Replayed: true；同键不同请求 422。 */
    @Test void idempotency() throws Exception {
        String id = released(10);
        assertProblem(call(planner(), op(id, "OP10", "dispatch"), "{\"stationCode\":\"ST-CNC-01\"}", null), 400, "idempotency-key-required");
        MvcResult a = call(planner(), op(id, "OP10", "dispatch"), "{\"stationCode\":\"ST-CNC-01\"}", "k-1");
        MvcResult b = call(planner(), op(id, "OP10", "dispatch"), "{\"stationCode\":\"ST-CNC-01\"}", "k-1");
        assertOk(b, 200);
        assertThat(b.getResponse().getHeader("Idempotent-Replayed")).isEqualTo("true");
        assertThat(b.getResponse().getContentAsString()).isEqualTo(a.getResponse().getContentAsString());
        assertProblem(call(planner(), op(id, "OP10", "dispatch"), "{\"stationCode\":\"ST-CNC-02\"}", "k-1"), 422, "idempotency-key-conflict");
    }

    // ------------------------------------------------------------------ 权限

    /** PLANNER 可派工、查工单进度与任务列表，但不能报工、开工、下达、冻结、取消（403）。 */
    @Test void plannerCanDispatchButNotModifyWorkOrders() throws Exception {
        String id = released(10);
        assertOk(dispatch(id, "OP10", "ST-CNC-01", null), 200);
        assertOk(mvc.perform(get("/workorders/" + id + "/progress").with(planner())).andReturn(), 200);
        assertOk(mvc.perform(get("/operation-tasks").with(planner())).andReturn(), 200);
        assertProblem(report(planner(), id, "OP10", "1", "\"1\""), 403, "access-denied");
        assertProblem(action(planner(), id, "OP10", "start", null), 403, "access-denied");
        assertProblem(call(planner(), "/workorders/" + id + "/hold", "{\"reason\":\"MANUAL\"}"), 403, "access-denied");
        assertProblem(call(planner(), "/workorders/" + id + "/cancel", "{}"), 403, "access-denied");
        String created = ReleaseFixtures.createOrder(jdbc, 1);
        assertProblem(call(planner(), "/workorders/" + created + "/release", "{}"), 403, "access-denied");
    }

    /** ADMIN（execution:dispatch）可派工；只有 workorder:write 的老令牌也可派工和开工（兼容期）；只读用户与操作工不能派工。 */
    @Test void dispatchPermissionMatrix() throws Exception {
        String id = released(10);
        RequestPostProcessor admin = user("admin", "workorder:read", "workorder:write", "masterdata:read", "execution:dispatch", "execution:report");
        assertOk(call(admin, op(id, "OP10", "dispatch"), "{\"stationCode\":\"ST-CNC-01\"}"), 200);
        assertOk(call(legacyWriter(), op(id, "OP20", "dispatch"), "{\"stationCode\":\"ST-ASM-01\"}"), 200);
        assertOk(action(legacyWriter(), id, "OP10", "start", null), 200);
        String other = released(10);
        assertProblem(call(user("viewer", "workorder:read"), op(other, "OP10", "dispatch"), "{\"stationCode\":\"ST-CNC-01\"}"), 403, "access-denied");
        assertProblem(call(worker("op-1"), op(other, "OP10", "dispatch"), "{\"stationCode\":\"ST-CNC-01\"}"), 403, "access-denied");
        assertProblem(action(user("viewer", "workorder:read"), other, "OP10", "start", null), 403, "access-denied");
    }

    // ------------------------------------------------------------------ 任务列表

    /** 按工位、状态、操作工过滤；计划开始早的排前；分页参数和状态取值非法时 400。 */
    @Test void listOperationTasks() throws Exception {
        String a = released(10), b = released(10);
        call(planner(), op(b, "OP10", "dispatch"), "{\"stationCode\":\"ST-CNC-01\",\"operator\":\"op-a\",\"plannedStart\":\"2026-10-15T08:00:00Z\",\"plannedEnd\":\"2026-10-15T12:00:00Z\"}");
        call(planner(), op(a, "OP10", "dispatch"), "{\"stationCode\":\"ST-CNC-01\",\"plannedStart\":\"2026-10-16T08:00:00Z\"}");
        dispatch(a, "OP20", "ST-ASM-01", "op-a");
        JsonNode list = json(mvc.perform(get("/operation-tasks").param("station", "ST-CNC-01").with(worker("op-1"))).andReturn()).at("/data");
        assertThat(list).hasSize(2);
        assertThat(list.get(0).get("workOrderNo").asText()).isEqualTo(b);
        assertThat(json(mvc.perform(get("/operation-tasks").param("operator", "op-a").param("status", "PENDING").with(worker("op-1"))).andReturn()).at("/data")).hasSize(1);
        assertThat(json(mvc.perform(get("/operation-tasks").param("size", "1").param("page", "1").with(worker("op-1"))).andReturn()).at("/data")).hasSize(1);
        assertProblem(mvc.perform(get("/operation-tasks").param("size", "500").with(worker("op-1"))).andReturn(), 400, "validation-error");
        assertProblem(mvc.perform(get("/operation-tasks").param("status", "WHATEVER").with(worker("op-1"))).andReturn(), 400, "validation-error");
        assertProblem(mvc.perform(get("/operation-tasks").with(user("x", "execution:report"))).andReturn(), 403, "access-denied");
    }
}
