package com.smartmanufacturing.workorder;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * 工序报工合并门槛（真实 MySQL 8.4，经 HTTP 入口）：并发不超报、幂等重放、冲销一致性（含 COMPLETED→IN_PROGRESS）、
 * 上下游约束、DECIMAL 精度、手工完工、CLOSED 拒绝。工艺快照为两道工序 OP10 → OP20。
 */
@Testcontainers
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class OperationReportingMySqlIT {
    @Container @ServiceConnection
    static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.4");

    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired ObjectMapper mapper;
    @Autowired io.micrometer.core.instrument.MeterRegistry meters;
    @Autowired WorkOrderReleaseService release;
    @Autowired LocalMaterialReservation stub;
    @MockitoBean MasterdataClient masterdata;
    @MockitoBean DeviceClient devices;
    @MockitoBean org.springframework.security.oauth2.jwt.JwtDecoder decoder;

    @BeforeEach void setUp() { ReleaseFixtures.clear(jdbc); ReleaseFixtures.stubMasterdata(masterdata); stub.reset(); }

    private RequestPostProcessor operator() {
        return jwt().jwt(j -> j.subject("op-1")).authorities(new SimpleGrantedAuthority("workorder:write"), new SimpleGrantedAuthority("workorder:read"));
    }

    private String released(int qty) {
        String id = ReleaseFixtures.createOrder(jdbc, qty);
        release.release(id, "rel-" + id, "planner");
        return id;
    }

    private MvcResult call(String path, String key, String body) throws Exception {
        var req = post(path).with(operator()).contentType(MediaType.APPLICATION_JSON).content(body);
        if (key != null) req.header("Idempotency-Key", key);
        return mvc.perform(req).andReturn();
    }

    private MvcResult report(String id, String op, String good, String defect, String scrap) throws Exception {
        return call("/workorders/" + id + "/operations/" + op + "/reports", UUID.randomUUID().toString(),
            "{\"goodQty\":\"" + good + "\",\"defectQty\":\"" + defect + "\",\"scrapQty\":\"" + scrap + "\",\"uom\":\"PCS\",\"lotNo\":\"LOT-1\"}");
    }

    private MvcResult reverse(String id, String reportId) throws Exception {
        return call("/workorders/" + id + "/reports/" + reportId + "/reversal", UUID.randomUUID().toString(), "{\"reason\":\"误报\"}");
    }

    private JsonNode json(MvcResult r) throws Exception { return mapper.readTree(r.getResponse().getContentAsString(StandardCharsets.UTF_8)); }
    private String reportId(MvcResult r) throws Exception { return json(r).at("/data/report/reportId").asText(); }
    private String status(String id) { return jdbc.queryForObject("select status from work_order where id = ?", String.class, id); }
    private BigDecimal good(String id, String op) {
        return jdbc.queryForObject("select good_qty from wo_operation_progress where order_id = ? and op_no = ?", BigDecimal.class, id, op);
    }
    private List<JsonNode> events(String id, String type) throws Exception {
        List<JsonNode> out = new ArrayList<>();
        for (String p : jdbc.queryForList("select payload from outbox_event where aggregate_id = ? and event_type = ? order by occurred_at", String.class, id, type))
            out.add(mapper.readTree(p));
        return out;
    }
    private void assertProblem(MvcResult r, int status, String type) throws Exception {
        assertThat(r.getResponse().getStatus()).isEqualTo(status);
        assertThat(r.getResponse().getContentType()).startsWith("application/problem+json");
        assertThat(json(r).get("type").asText()).isEqualTo(type);
    }

    private double transitions(String from, String to) {
        var c = meters.find("mes.workorder.status.transition").tag("from", from).tag("to", to).counter();
        return c == null ? 0 : c.count();
    }

    private double counter(String name, String tag, String value) {
        var c = meters.find(name).tag(tag, value).counter();
        return c == null ? 0 : c.count();
    }

    /** plannedQty = completedQty + shortfallQty（精确小数）。 */
    private static void assertSumsToPlanned(JsonNode e) {
        assertThat(new BigDecimal(e.get("completedQty").asText()).add(new BigDecimal(e.get("shortfallQty").asText())))
            .isEqualByComparingTo(new BigDecimal(e.get("plannedQty").asText()));
    }

    /** 工单状态事件按 aggregateVersion 排序（消费端的排序依据；occurredAt 仅用于展示）。 */
    private List<JsonNode> stateEvents(String id) throws Exception {
        List<JsonNode> out = new ArrayList<>();
        for (var row : jdbc.queryForList("select event_type, payload from outbox_event where aggregate_id = ? and topic = 'mes-workorder-event'", id)) {
            var n = (com.fasterxml.jackson.databind.node.ObjectNode) mapper.readTree((String) row.get("payload"));
            n.put("_type", (String) row.get("event_type"));
            out.add(n);
        }
        out.sort(Comparator.comparingLong(n -> n.get("aggregateVersion").asLong()));
        return out;
    }

    /** 门槛：完工 → 冲销 → 再报工至完工，事件依次为 Completed、Reopened、Completed，aggregateVersion 递增且 completedQty 正确。 */
    @Test void completeReverseRecompleteEmitsVersionedEvents() throws Exception {
        String id = released(10);
        report(id, "OP10", "10", "0", "0");
        report(id, "OP20", "6", "0", "0");
        String last = reportId(report(id, "OP20", "4", "0", "0"));
        assertThat(status(id)).isEqualTo("COMPLETED");
        assertThat(reverse(id, last).getResponse().getStatus()).isEqualTo(201);
        assertThat(status(id)).isEqualTo("IN_PROGRESS");
        report(id, "OP20", "4", "0", "0");
        assertThat(status(id)).isEqualTo("COMPLETED");

        List<JsonNode> ev = stateEvents(id);
        assertThat(ev).extracting(n -> n.get("_type").asText())
            .containsExactly("WorkOrderReleased", "WorkOrderStarted", "WorkOrderCompleted", "WorkOrderReopened", "WorkOrderCompleted");
        for (int i = 1; i < ev.size(); i++)
            assertThat(ev.get(i).get("aggregateVersion").asLong()).isGreaterThan(ev.get(i - 1).get("aggregateVersion").asLong());
        assertThat(ev.get(ev.size() - 1).get("aggregateVersion").asLong())
            .isEqualTo(jdbc.queryForObject("select version from work_order where id = ?", Long.class, id));

        JsonNode c1 = ev.get(2), reopened = ev.get(3), c2 = ev.get(4);
        assertThat(c1.get("completedQty").asText()).isEqualTo("10.000000");
        assertThat(c1.get("completionMode").asText()).isEqualTo("AUTO");
        assertSumsToPlanned(c1);
        assertThat(reopened.get("reversedReportId").asText()).isEqualTo(last);
        assertThat(reopened.get("completedQty").asText()).isEqualTo("6.000000");
        assertThat(reopened.get("uom").asText()).isEqualTo("PCS");
        assertThat(reopened.get("workOrderNo").asText()).isEqualTo(id);
        assertThat(reopened.has("eventId")).isTrue();
        assertThat(reopened.has("occurredAt")).isTrue();
        assertThat(jdbc.queryForObject("select tag from outbox_event where aggregate_id = ? and event_type = 'WorkOrderReopened'", String.class, id)).isEqualTo("WorkOrderReopened");
        assertThat(c2.get("completedQty").asText()).isEqualTo("10.000000");
        assertThat(c2.get("shortfallQty").asText()).isEqualTo("0");
        assertSumsToPlanned(c2);
    }

    /** 门槛 1：N 个线程并发向同一工序报工，成功数之和恰好等于上限，绝不超报；累计与流水合计一致。 */
    @Test void concurrentReportsNeverExceedLimit() throws Exception {
        String id = released(10);
        double ok0 = counter("mes.workorder.report", "result", "ok"), over0 = counter("mes.workorder.report", "result", "over_report");
        double prog0 = transitions("RELEASED", "IN_PROGRESS");
        long lock0 = meters.get("mes.workorder.report.lock.wait").timer().count();
        double defect0 = meters.find("mes.workorder.report.qty").tag("kind", "defect").tag("uom", "PCS").counter() == null ? 0 : meters.get("mes.workorder.report.qty").tag("kind", "defect").tag("uom", "PCS").counter().count();
        int threads = 12;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<Integer>> fs = new ArrayList<>();
        for (int i = 0; i < threads; i++) fs.add(pool.submit(() -> { start.await(); return report(id, "OP10", "1", "0.5", "0.5").getResponse().getStatus(); }));
        start.countDown();
        List<Integer> statuses = new ArrayList<>();
        for (var f : fs) statuses.add(f.get(60, TimeUnit.SECONDS));
        pool.shutdown();

        assertThat(statuses).containsOnly(201, 422);
        assertThat(statuses).filteredOn(s -> s == 201).hasSize(5);
        BigDecimal total = jdbc.queryForObject("select good_qty + defect_qty + scrap_qty from wo_operation_progress where order_id = ? and op_no = 'OP10'", BigDecimal.class, id);
        BigDecimal ledger = jdbc.queryForObject("select sum(good_qty + defect_qty + scrap_qty) from wo_operation_report where order_id = ? and op_no = 'OP10'", BigDecimal.class, id);
        assertThat(total).isEqualByComparingTo("10").isEqualByComparingTo(ledger);
        assertThat(status(id)).isEqualTo("IN_PROGRESS");
        var started = events(id, "WorkOrderStarted");
        assertThat(started).hasSize(1);
        assertThat(started.get(0).get("workOrderNo").asText()).isEqualTo(id);
        assertThat(started.get(0).has("eventId")).isTrue();
        assertThat(started.get(0).has("occurredAt")).isTrue();
        assertThat(events(id, "DefectReported")).hasSize(5);
        // 指标：成功 5 次、超限 7 次；不良数量合计 2.5；状态转换 1 次；每次请求都计时行锁等待
        assertThat(counter("mes.workorder.report", "result", "ok") - ok0).isEqualTo(5);
        assertThat(counter("mes.workorder.report", "result", "over_report") - over0).isEqualTo(7);
        double replay0 = counter("mes.workorder.report", "result", "replay"), closed0 = counter("mes.workorder.report", "result", "workorder_closed");
        String path = "/workorders/" + id + "/operations/OP20/reports";
        String body = "{\"goodQty\":1,\"defectQty\":0,\"scrapQty\":0,\"uom\":\"PCS\"}";
        call(path, "m-1", body); call(path, "m-1", body);
        assertThat(counter("mes.workorder.report", "result", "replay") - replay0).isEqualTo(1);
        jdbc.update("update work_order set status = 'CLOSED' where id = ?", id);
        call(path, "m-2", body);
        assertThat(counter("mes.workorder.report", "result", "workorder_closed") - closed0).isEqualTo(1);
        assertThat(meters.get("mes.workorder.outbox.pending").gauge().value())
            .isEqualTo(jdbc.queryForObject("select count(*) from outbox_event where status = 'NEW'", Long.class).doubleValue());
        assertThat(transitions("RELEASED", "IN_PROGRESS") - prog0).isEqualTo(1);
        assertThat(meters.get("mes.workorder.report.qty").tag("kind", "defect").tag("uom", "PCS").counter().count() - defect0).isEqualTo(2.5);
        assertThat(meters.get("mes.workorder.report.lock.wait").timer().count() - lock0).isEqualTo(threads + 3); // 并发 12 次 + 重放校验 2 次 + 关闭校验 1 次
    }

    /** 门槛 2：同键重放返回首次的状态与响应体，不重复写入；同键不同请求 422；缺键 400。 */
    @Test void idempotentReplay() throws Exception {
        String id = released(10);
        String path = "/workorders/" + id + "/operations/OP10/reports";
        String body = "{\"goodQty\":3,\"defectQty\":0,\"scrapQty\":0,\"uom\":\"PCS\"}";
        MvcResult first = call(path, "k-1", body), again = call(path, "k-1", body);
        assertThat(first.getResponse().getStatus()).isEqualTo(201);
        assertThat(again.getResponse().getStatus()).isEqualTo(201);
        assertThat(again.getResponse().getHeader("Idempotent-Replayed")).isEqualTo("true");
        assertThat(again.getResponse().getContentAsString()).isEqualTo(first.getResponse().getContentAsString());
        assertThat(jdbc.queryForObject("select count(*) from wo_operation_report where order_id = ?", Integer.class, id)).isEqualTo(1);
        assertThat(good(id, "OP10")).isEqualByComparingTo("3");

        assertProblem(call(path, "k-1", body.replace("3", "4")), 422, "idempotency-key-conflict");
        assertProblem(call(path, null, body), 400, "idempotency-key-required");

        // 冲销与手工完工同样幂等
        String rid = reportId(first);
        String rpath = "/workorders/" + id + "/reports/" + rid + "/reversal";
        MvcResult r1 = call(rpath, "rv-1", "{\"reason\":\"误报\"}"), r2 = call(rpath, "rv-1", "{\"reason\":\"误报\"}");
        assertThat(r2.getResponse().getStatus()).isEqualTo(201);
        assertThat(r2.getResponse().getContentAsString()).isEqualTo(r1.getResponse().getContentAsString());
        assertThat(jdbc.queryForObject("select count(*) from wo_operation_report where reversed_report_id = ?", Integer.class, rid)).isEqualTo(1);
    }

    /** 报工未带 lotNo 时缺省为工单号：落库、响应、DefectReported 与 DefectReversed 均使用工单号；lotNo 入口 trim，空白视同未传。 */
    @Test void lotNoDefaultsToWorkOrderNo() throws Exception {
        String id = released(20);
        String path = "/workorders/" + id + "/operations/OP10/reports";
        // 纯空白批次号 trim 后为空，视同未传，缺省为工单号（不再 400）
        MvcResult blank = call(path, UUID.randomUUID().toString(),
            "{\"goodQty\":\"1\",\"defectQty\":\"0\",\"scrapQty\":\"0\",\"uom\":\"PCS\",\"lotNo\":\"  \"}");
        assertThat(blank.getResponse().getStatus()).isEqualTo(201);
        assertThat(jdbc.queryForObject("select lot_no from wo_operation_report where report_id = ?", String.class, reportId(blank))).isEqualTo(id);
        // 首尾空白被去掉后落库
        MvcResult trimmed = call(path, UUID.randomUUID().toString(),
            "{\"goodQty\":\"1\",\"defectQty\":\"0\",\"scrapQty\":\"0\",\"uom\":\"PCS\",\"lotNo\":\" LOT 1 \"}");
        assertThat(trimmed.getResponse().getStatus()).isEqualTo(201);
        assertThat(jdbc.queryForObject("select lot_no from wo_operation_report where report_id = ?", String.class, reportId(trimmed))).isEqualTo("LOT 1");
        // 超长批次号（> 64，与 lot_no 列宽一致）被拒绝
        assertProblem(call(path, UUID.randomUUID().toString(),
            "{\"goodQty\":\"1\",\"defectQty\":\"0\",\"scrapQty\":\"0\",\"uom\":\"PCS\",\"lotNo\":\"" + "L".repeat(65) + "\"}"), 400, "validation-error");
        MvcResult r = call(path, UUID.randomUUID().toString(), "{\"goodQty\":\"8\",\"defectQty\":\"2\",\"scrapQty\":\"0\",\"uom\":\"PCS\"}");
        assertThat(r.getResponse().getStatus()).isEqualTo(201);
        String rid = reportId(r);
        assertThat(json(r).at("/data/report/lotNo").asText()).isEqualTo(id);
        assertThat(jdbc.queryForObject("select lot_no from wo_operation_report where report_id = ?", String.class, rid)).isEqualTo(id);
        var reported = events(id, "DefectReported");
        assertThat(reported).hasSize(1);
        assertThat(reported.get(0).get("lotNo").asText()).isEqualTo(id);
        reverse(id, rid);
        var reversed = events(id, "DefectReversed");
        assertThat(reversed).hasSize(1);
        assertThat(reversed.get(0).get("lotNo").asText()).isEqualTo(id);
    }

    /** 门槛 3：冲销后累计、状态与事件一致；COMPLETED→IN_PROGRESS；只能冲销一次；全部冲销后仍为 IN_PROGRESS。 */
    @Test void reversalKeepsProgressStatusAndEventsConsistent() throws Exception {
        String id = released(10);
        String a = reportId(report(id, "OP10", "10", "0", "0"));
        MvcResult b = report(id, "OP20", "9", "1", "0");
        assertThat(status(id)).isEqualTo("IN_PROGRESS");
        String c = reportId(report(id, "OP10", "0", "0", "0.000001"));
        assertThat(c).isEmpty(); // 超上限（10 已报满）
        // OP20 再报 0 个合格无法完工；冲销 OP20 不良报工后重报 10 合格 → 完工
        String bid = reportId(b);
        JsonNode rev = json(reverse(id, bid));
        assertThat(rev.at("/data/report/type").asText()).isEqualTo("REVERSAL");
        assertThat(rev.at("/data/report/goodQty").asText()).isEqualTo("-9.000000");
        assertThat(rev.at("/data/report/reversedReportId").asText()).isEqualTo(bid);
        var defectRev = events(id, "DefectReversed");
        assertThat(defectRev).hasSize(1);
        assertThat(defectRev.get(0).get("reversedReportId").asText()).isEqualTo(bid);
        assertThat(defectRev.get(0).get("reversalId").asText()).isEqualTo(rev.at("/data/report/reportId").asText());
        assertThat(defectRev.get(0).has("originalReportId")).isFalse();
        assertThat(defectRev.get(0).has("reportId")).isFalse();
        assertThat(defectRev.get(0).get("defectQty").asText()).isEqualTo("1.000000");
        assertThat(defectRev.get(0).get("materialCode").asText()).isEqualTo(ReleaseFixtures.PRODUCT);
        assertThat(defectRev.get(0).get("lotNo").asText()).isEqualTo("LOT-1");
        assertThat(defectRev.get(0).has("occurredAt")).isTrue();
        assertThat(jdbc.queryForObject("select topic from outbox_event where event_type = 'DefectReversed' and aggregate_id = ?", String.class, id)).isEqualTo("mes-workorder-quality");

        String d = reportId(report(id, "OP20", "10", "0", "0"));
        assertThat(status(id)).isEqualTo("COMPLETED");
        var completed = events(id, "WorkOrderCompleted");
        assertThat(completed).hasSize(1);
        assertThat(completed.get(0).get("shortfallQty").asText()).isEqualTo("0");
        assertThat(completed.get(0).get("completionMode").asText()).isEqualTo("AUTO");
        assertThat(completed.get(0).get("uom").asText()).isEqualTo("PCS");
        assertThat(completed.get(0).get("completedQty").asText()).isEqualTo("10.000000");
        assertThat(completed.get(0).has("reason")).isFalse();
        assertSumsToPlanned(completed.get(0));
        assertThat(jdbc.queryForObject("select tag from outbox_event where event_type = 'WorkOrderCompleted' and aggregate_id = ?", String.class, id)).isEqualTo("WorkOrderCompleted");

        // 完工后冲销 → IN_PROGRESS，审计记录 REPORT_REVERSED；冲销数量以正数计入 reversal_qty，report_qty 不减少
        var revGood = meters.find("mes.workorder.reversal.qty").tag("kind", "good").tag("uom", "PCS").counter();
        double revGood0 = revGood == null ? 0 : revGood.count();
        double repGood0 = meters.get("mes.workorder.report.qty").tag("kind", "good").tag("uom", "PCS").counter().count();
        assertThat(reverse(id, d).getResponse().getStatus()).isEqualTo(201);
        assertThat(meters.get("mes.workorder.reversal.qty").tag("kind", "good").tag("uom", "PCS").counter().count() - revGood0).isEqualTo(10.0);
        assertThat(meters.get("mes.workorder.report.qty").tag("kind", "good").tag("uom", "PCS").counter().count()).isEqualTo(repGood0);
        assertThat(status(id)).isEqualTo("IN_PROGRESS");
        assertThat(good(id, "OP20")).isEqualByComparingTo("0");
        assertThat(jdbc.queryForObject("select count(*) from work_order_status_log where order_id = ? and from_status = 'COMPLETED' and to_status = 'IN_PROGRESS' and reason = 'REPORT_REVERSED'", Integer.class, id)).isEqualTo(1);
        // 只能冲销一次；冲销记录本身不能冲销
        assertProblem(reverse(id, d), 409, "report-already-reversed");
        assertProblem(reverse(id, rev.at("/data/report/reportId").asText()), 409, "report-already-reversed");

        // 全部冲销后仍为 IN_PROGRESS，累计归零
        assertThat(reverse(id, a).getResponse().getStatus()).isEqualTo(201);
        assertThat(status(id)).isEqualTo("IN_PROGRESS");
        assertThat(good(id, "OP10")).isEqualByComparingTo("0");
        assertThat(jdbc.queryForObject("select sum(effective_reports) from wo_operation_progress where order_id = ?", Integer.class, id)).isZero();
        assertThat(jdbc.queryForObject("select sum(good_qty) from wo_operation_report where order_id = ?", BigDecimal.class, id)).isEqualByComparingTo("0");
    }

    /** 门槛 4：冲销上游后其累计合格数不得小于下游已报总数，否则 409 downstream-reported 且不落任何数据。 */
    @Test void upstreamReversalBlockedByDownstream() throws Exception {
        String id = released(10);
        String six = reportId(report(id, "OP10", "6", "0", "0"));
        String four = reportId(report(id, "OP10", "4", "0", "0"));
        report(id, "OP20", "5", "0", "1");
        // 下游已报 6：冲销 4 后上游合格 6 ≥ 6，允许
        assertThat(reverse(id, four).getResponse().getStatus()).isEqualTo(201);
        // 再冲销 6 后上游合格 0 < 6，拒绝
        MvcResult blocked = reverse(id, six);
        assertProblem(blocked, 409, "downstream-reported");
        assertThat(json(blocked).at("/data/downstreamReported").asText()).isEqualTo("6.000000");
        assertThat(good(id, "OP10")).isEqualByComparingTo("6");
        assertThat(jdbc.queryForObject("select count(*) from wo_operation_report where reversed_report_id = ?", Integer.class, six)).isZero();
        // 下游上限随上游冲销收紧：OP20 已报 6 = 上道合格 6，再报即超限
        assertProblem(report(id, "OP20", "0.000001", "0", "0"), 422, "over-report");
    }

    /** 门槛 5：DECIMAL(18,6) 精度无损；上限按精确小数比较，无容差；超过 6 位小数 400。 */
    @Test void decimalPrecision() throws Exception {
        String id = released(3);
        JsonNode r = json(report(id, "OP10", "1.123456", "0.000001", "0.5"));
        assertThat(r.at("/data/report/goodQty").asText()).isEqualTo("1.123456");
        assertThat(r.at("/data/progress/operations/1/limit").asText()).isEqualTo("1.123456");
        assertThat(good(id, "OP10").toPlainString()).isEqualTo("1.123456");
        assertThat(jdbc.queryForObject("select defect_qty from wo_operation_report where order_id = ?", BigDecimal.class, id).toPlainString()).isEqualTo("0.000001");
        assertThat(events(id, "DefectReported").get(0).get("defectQty").asText()).isEqualTo("0.000001");

        MvcResult over = report(id, "OP20", "1.123457", "0", "0");
        assertProblem(over, 422, "over-report");
        assertThat(json(over).at("/data/limit").asText()).isEqualTo("1.123456");
        assertThat(report(id, "OP20", "1.123456", "0", "0").getResponse().getStatus()).isEqualTo(201);
        // 首道剩余 3 − 1.623457 = 1.376543，恰好报满允许
        assertThat(report(id, "OP10", "1.376543", "0", "0").getResponse().getStatus()).isEqualTo(201);
        assertProblem(report(id, "OP10", "0.000001", "0", "0"), 422, "over-report");
        assertProblem(report(id, "OP10", "0.0000001", "0", "0"), 400, "validation-error");
    }

    /** 同一工单内计量单位不一致返回 422 uom-mismatch，并计入 result="uom_mismatch"。 */
    @Test void uomMismatchIs422() throws Exception {
        String id = released(10);
        report(id, "OP10", "1", "0", "0");
        double before = counter("mes.workorder.report", "result", "uom_mismatch");
        MvcResult r = call("/workorders/" + id + "/operations/OP10/reports", UUID.randomUUID().toString(),
            "{\"goodQty\":1,\"defectQty\":0,\"scrapQty\":0,\"uom\":\"KG\"}");
        assertProblem(r, 422, "uom-mismatch");
        assertThat(json(r).at("/data/expectedUom").asText()).isEqualTo("PCS");
        assertThat(counter("mes.workorder.report", "result", "uom_mismatch") - before).isEqualTo(1);
    }

    /** 下达、冻结、恢复同样计入 mes_workorder_status_transition_total{from,to}；追加第二个冻结原因不计。 */
    @Test void lifecycleTransitionsAreCounted() {
        double rel0 = transitions("CREATED", "RELEASED"), hold0 = transitions("RELEASED", "ON_HOLD"), res0 = transitions("ON_HOLD", "RELEASED");
        String id = released(5);
        release.hold(id, com.smartmanufacturing.api.workorder.HoldReason.MANUAL, null, "u");
        release.hold(id, com.smartmanufacturing.api.workorder.HoldReason.LOT_HELD, null, "u");
        release.resume(id, com.smartmanufacturing.api.workorder.HoldReason.MANUAL, null, "u");
        release.resume(id, com.smartmanufacturing.api.workorder.HoldReason.LOT_HELD, null, "u");
        assertThat(transitions("CREATED", "RELEASED") - rel0).isEqualTo(1);
        assertThat(transitions("RELEASED", "ON_HOLD") - hold0).isEqualTo(1);
        assertThat(transitions("ON_HOLD", "RELEASED") - res0).isEqualTo(1);
        // 生命周期事件已带投递目标与 aggregateVersion
        assertThat(jdbc.queryForList("select tag from outbox_event where aggregate_id = ? and topic = 'mes-workorder-event' order by occurred_at", String.class, id))
            .contains("WorkOrderReleased", "WorkOrderHeld", "WorkOrderHoldReasonCleared", "WorkOrderResumed");
        assertThat(jdbc.queryForObject("select count(*) from outbox_event where aggregate_id = ? and (topic is null or tag is null)", Integer.class, id)).isZero();
    }

    /** 门槛 6：手工完工——存在未报工工序 409；成功后写审计、发 WorkOrderCompleted，shortfall = 计划 − 末道合格。 */
    @Test void manualComplete() throws Exception {
        String id = released(10);
        String path = "/workorders/" + id + "/complete";
        report(id, "OP10", "8", "1", "1");
        MvcResult blocked = call(path, UUID.randomUUID().toString(), "{\"reason\":\"客户提前提货\"}");
        assertProblem(blocked, 409, "operations-unreported");
        assertThat(json(blocked).at("/data/operations/0").asText()).isEqualTo("OP20");
        assertProblem(call(path, UUID.randomUUID().toString(), "{\"reason\":\"\"}"), 400, "validation-error");

        report(id, "OP20", "7.5", "0", "0");
        MvcResult ok = call(path, "c-1", "{\"reason\":\"客户提前提货\"}");
        assertThat(ok.getResponse().getStatus()).isEqualTo(200);
        assertThat(json(ok).at("/data/shortfall").asText()).isEqualTo("2.500000");
        assertThat(status(id)).isEqualTo("COMPLETED");
        assertThat(jdbc.queryForObject("select note from work_order_status_log where order_id = ? and to_status = 'COMPLETED' and reason = 'MANUAL_COMPLETE'", String.class, id)).isEqualTo("客户提前提货");
        var ev = events(id, "WorkOrderCompleted");
        assertThat(ev).hasSize(1);
        assertThat(ev.get(0).get("shortfallQty").asText()).isEqualTo("2.500000");
        assertThat(ev.get(0).get("completionMode").asText()).isEqualTo("MANUAL");
        assertThat(ev.get(0).get("plannedQty").asText()).isEqualTo("10.000000");
        assertThat(ev.get(0).get("completedQty").asText()).isEqualTo("7.500000");
        assertThat(ev.get(0).get("uom").asText()).isEqualTo("PCS");
        assertSumsToPlanned(ev.get(0));
        assertThat(ev.get(0).get("reason").asText()).isEqualTo("客户提前提货");
        // 重放返回首次结果；已完工不能再报工
        assertThat(call(path, "c-1", "{\"reason\":\"客户提前提货\"}").getResponse().getContentAsString()).isEqualTo(ok.getResponse().getContentAsString());
        assertProblem(report(id, "OP20", "1", "0", "0"), 409, "workorder-state-conflict");
    }

    /** 门槛 7：CLOSED 工单的报工、冲销、完工均返回 409 workorder-closed。 */
    @Test void closedWorkOrderRejected() throws Exception {
        String id = released(10);
        String rid = reportId(report(id, "OP10", "2", "0", "0"));
        jdbc.update("update work_order set status = 'CLOSED' where id = ?", id);
        assertProblem(report(id, "OP10", "1", "0", "0"), 409, "workorder-closed");
        assertProblem(reverse(id, rid), 409, "workorder-closed");
        assertProblem(call("/workorders/" + id + "/complete", UUID.randomUUID().toString(), "{\"reason\":\"x\"}"), 409, "workorder-closed");
        assertThat(good(id, "OP10")).isEqualByComparingTo("2");
    }
}
