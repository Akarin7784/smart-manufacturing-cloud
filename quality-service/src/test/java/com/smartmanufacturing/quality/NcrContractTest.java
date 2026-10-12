package com.smartmanufacturing.quality;

import com.fasterxml.jackson.databind.JsonNode;
import io.micrometer.core.instrument.MeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * NCR 接口契约测试（H2）：每个响应（成功与错误）都按 docs/api/quality-openapi.yaml 校验状态码、媒体类型与响应体 schema，
 * 断言 7 种 ProblemType 全部被触发且 type 与状态码匹配；同时校验 outbox 事件载荷符合契约中的事件 schema。
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class NcrContractTest {
    private static final String NCRS = "/api/quality/ncrs";
    private static final String ONE = NCRS + "/{ncrNo}";

    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired DefectEventHandler handler;
    @Autowired OutboxPublisher publisher;
    @Autowired RecordingEventSender sender;
    @Autowired MeterRegistry registry;

    /** 已触发的 ProblemType，最终必须覆盖契约中的全部取值。 */
    private final Set<String> covered = new TreeSet<>();

    @BeforeEach void clean() {
        publisher.publishBatch();
        sender.clear();
    }

    /** 质检员令牌：quality:read + quality:write，sub=qa-user。 */
    private static RequestPostProcessor inspector() {
        return jwt().jwt(j -> j.subject("qa-user"))
            .authorities(new SimpleGrantedAuthority("quality:read"), new SimpleGrantedAuthority("quality:write"));
    }

    /** 只读令牌：只有 quality:read。 */
    private static RequestPostProcessor reader() {
        return jwt().jwt(j -> j.subject("viewer")).authorities(new SimpleGrantedAuthority("quality:read"));
    }

    /** 发送请求，按契约校验响应；若为 problem+json 则记录 type 并核对 status 字段与 HTTP 状态一致。 */
    private JsonNode call(MockHttpServletRequestBuilder req, RequestPostProcessor who, String template, String method, int expected) throws Exception {
        if (who != null) req = req.with(who);
        MvcResult r = mvc.perform(req).andReturn();
        assertThat(r.getResponse().getStatus()).as(method + " " + template + "：" + r.getResponse().getContentAsString()).isEqualTo(expected);
        JsonNode body = OpenApiContract.assertConforms(template, method, r);
        if (expected >= 400 && body.has("type") && !"about:blank".equals(body.get("type").asText())) {
            covered.add(body.get("type").asText());
            assertThat(body.get("status").asInt()).isEqualTo(expected);
        }
        return body;
    }
    private JsonNode call(MockHttpServletRequestBuilder req, String template, String method, int expected) throws Exception {
        return call(req, inspector(), template, method, expected);
    }

    private static MockHttpServletRequestBuilder postJson(String url, String key, String body) {
        MockHttpServletRequestBuilder b = post(url).contentType(MediaType.APPLICATION_JSON);
        if (key != null) b.header("Idempotency-Key", key);
        return body == null ? b : b.content(body);
    }
    private static String svc(String path) { return path.replaceFirst("^/api", ""); }
    private static String key() { return UUID.randomUUID().toString(); }
    private static String disposition(String type, String qty, String uom) {
        return "{\"type\":\"" + type + "\",\"qty\":\"" + qty + "\",\"uom\":\"" + uom + "\",\"reason\":\"契约测试\"}";
    }

    /** 经事件建一张 NCR，返回编号。 */
    private String openNcr(String qty) {
        String reportId = "RPT-" + UUID.randomUUID();
        assertThat(handler.handle("DefectReported", DefectEvents.id(), DefectEvents.reported(DefectEvents.id(), reportId, "WO-CT", qty, "件")))
            .isEqualTo(DefectEventHandler.Result.OK);
        return jdbc.queryForObject("select ncr_no from ncr where source_report_id = ?", String.class, reportId);
    }

    private double api(String result) {
        var c = registry.find("mes.quality.api").tag("result", result).counter();
        return c == null ? 0 : c.count();
    }

    /** 完整生命周期 + 全部错误类型：每一步都按契约校验。 */
    @Test void everyResponseConformsAndAllProblemTypesAreCovered() throws Exception {
        String a = openNcr("10");
        String base = svc(NCRS) + "/" + a;

        // 查询：列表、筛选、详情；ncr-not-found（编号不存在、格式不符）。
        JsonNode page = call(get(svc(NCRS)).param("status", "OPEN").param("workOrderNo", "WO-CT"), NCRS, "get", 200);
        assertThat(page.get("total").asLong()).isPositive();
        call(get(svc(NCRS)).param("status", "BOGUS"), NCRS, "get", 400);
        call(get(svc(NCRS)).param("size", "0"), NCRS, "get", 400);
        call(get(svc(NCRS)).param("page", "x"), NCRS, "get", 400);
        JsonNode detail = call(get(base), ONE, "get", 200);
        assertThat(detail.get("status").asText()).isEqualTo("OPEN");
        assertThat(detail.get("version").asLong()).isEqualTo(1);
        assertThat(detail.get("voidReason").isNull()).isTrue();
        call(get(svc(NCRS) + "/NCR-20990101-9999"), ONE, "get", 404);
        call(get(svc(NCRS) + "/not-an-ncr"), ONE, "get", 404);

        // invalid-status-transition：OPEN 直接关闭、OPEN 直接处置（409）。
        call(postJson(base + "/close", key(), null), ONE + "/close", "post", 409);
        call(postJson(base + "/dispositions", key(), disposition("REWORK", "1", "件")), ONE + "/dispositions", "post", 409);
        // validation-error：缺少 Idempotency-Key（400）。
        call(postJson(base + "/review", null, null), ONE + "/review", "post", 400);

        // 评审：OPEN → UNDER_REVIEW；同键重放逐字节一致；换键再评审返回 200（已在目标状态）。
        String k = key();
        MvcResult first = mvc.perform(postJson(base + "/review", k, null).with(inspector())).andReturn();
        JsonNode reviewed = OpenApiContract.assertConforms(ONE + "/review", "post", first);
        assertThat(reviewed.get("status").asText()).isEqualTo("UNDER_REVIEW");
        double replays = api("replay");
        MvcResult replay = mvc.perform(postJson(base + "/review", k, null).with(inspector())).andReturn();
        assertThat(replay.getResponse().getStatus()).isEqualTo(200);
        assertThat(replay.getResponse().getContentAsString()).isEqualTo(first.getResponse().getContentAsString());
        assertThat(api("replay")).isEqualTo(replays + 1);
        assertThat(call(postJson(base + "/review", key(), null), ONE + "/review", "post", 200).get("version").asLong()).isEqualTo(2);
        // idempotency-key-conflict：同一个键挪用到另一张 NCR（422）。
        String other = openNcr("1");
        call(postJson(svc(NCRS) + "/" + other + "/review", k, null), ONE + "/review", "post", 422);

        // 处置校验：类型非法、数量非法（400）；uom-mismatch、over-dispose（422）。
        call(postJson(base + "/dispositions", key(), disposition("RETURN_TO_SUPPLIER", "1", "件")), ONE + "/dispositions", "post", 400);
        call(postJson(base + "/dispositions", key(), disposition("SCRAP", "-1", "件")), ONE + "/dispositions", "post", 400);
        call(postJson(base + "/dispositions", key(), "{not json"), ONE + "/dispositions", "post", 400);
        call(postJson(base + "/dispositions", key(), disposition("SCRAP", "1", "kg")), ONE + "/dispositions", "post", 422);
        call(postJson(base + "/dispositions", key(), disposition("SCRAP", "10.000001", "件")), ONE + "/dispositions", "post", 422);

        // 部分处置：SCRAP 4（201，仍 UNDER_REVIEW），同键重放一致；同键不同内容 422。
        String dk = key();
        MvcResult d1 = mvc.perform(postJson(base + "/dispositions", dk, disposition("SCRAP", "4", "件")).with(inspector())).andReturn();
        JsonNode afterScrap = OpenApiContract.assertConforms(ONE + "/dispositions", "post", d1);
        assertThat(d1.getResponse().getStatus()).isEqualTo(201);
        assertThat(afterScrap.get("status").asText()).isEqualTo("UNDER_REVIEW");
        assertThat(afterScrap.get("disposedQty").asText()).isEqualTo("4");
        assertThat(afterScrap.get("dispositions").get(0).get("operator").asText()).isEqualTo("qa-user");
        MvcResult d1Replay = mvc.perform(postJson(base + "/dispositions", dk, disposition("SCRAP", "4", "件")).with(inspector())).andReturn();
        assertThat(d1Replay.getResponse().getStatus()).isEqualTo(201);
        assertThat(d1Replay.getResponse().getContentAsString()).isEqualTo(d1.getResponse().getContentAsString());
        call(postJson(base + "/dispositions", dk, disposition("SCRAP", "5", "件")), ONE + "/dispositions", "post", 422);
        assertThat(jdbc.queryForObject("select count(*) from ncr_disposition where ncr_no = ?", Integer.class, a)).isEqualTo(1);

        // 剩余 6 分两次处置：累计等于不良量后自动 DISPOSED；DISPOSED 后再处置 409。
        call(postJson(base + "/dispositions", key(), disposition("REWORK", "5.5", "件")), ONE + "/dispositions", "post", 201);
        JsonNode disposed = call(postJson(base + "/dispositions", key(), disposition("CONCESSION", "0.5", "件")), ONE + "/dispositions", "post", 201);
        assertThat(disposed.get("status").asText()).isEqualTo("DISPOSED");
        assertThat(disposed.get("dispositions")).hasSize(3);
        call(postJson(base + "/dispositions", key(), disposition("SCRAP", "1", "件")), ONE + "/dispositions", "post", 409);
        call(postJson(base + "/review", key(), null), ONE + "/review", "post", 409);

        // 关闭：DISPOSED → CLOSED；再次关闭 200（已在目标状态）。
        assertThat(call(postJson(base + "/close", key(), null), ONE + "/close", "post", 200).get("status").asText()).isEqualTo("CLOSED");
        assertThat(call(postJson(base + "/close", key(), null), ONE + "/close", "post", 200).get("status").asText()).isEqualTo("CLOSED");
        call(postJson(svc(NCRS) + "/NCR-20990101-9999/close", key(), null), ONE + "/close", "post", 404);
        call(postJson(svc(NCRS) + "/NCR-20990101-9999/dispositions", key(), disposition("SCRAP", "1", "件")), ONE + "/dispositions", "post", 404);

        // ncr-voided：未处置的 NCR 被冲销后，评审、处置、关闭均 409。
        String reportB = "RPT-" + UUID.randomUUID();
        handler.handle("DefectReported", DefectEvents.id(), DefectEvents.reported(DefectEvents.id(), reportB, "WO-CT", "3", "件"));
        handler.handle("DefectReversed", DefectEvents.id(), DefectEvents.reversed(DefectEvents.id(), reportB, "REV-B"));
        String b = jdbc.queryForObject("select ncr_no from ncr where source_report_id = ?", String.class, reportB);
        JsonNode voided = call(get(svc(NCRS) + "/" + b), ONE, "get", 200);
        assertThat(voided.get("status").asText()).isEqualTo("VOIDED");
        assertThat(voided.get("voidReason").asText()).isEqualTo("REVERSED");
        call(postJson(svc(NCRS) + "/" + b + "/review", key(), null), ONE + "/review", "post", 409);
        call(postJson(svc(NCRS) + "/" + b + "/dispositions", key(), disposition("SCRAP", "1", "件")), ONE + "/dispositions", "post", 409);
        call(postJson(svc(NCRS) + "/" + b + "/close", key(), null), ONE + "/close", "post", 409);

        // 暂存冲销与冲突查询。
        handler.handle("DefectReversed", DefectEvents.id(), DefectEvents.reversed(DefectEvents.id(), "RPT-MISSING-" + UUID.randomUUID(), "REV-M"));
        JsonNode pending = call(get(svc("/api/quality/pending-reversals")).param("status", "WAITING"), "/api/quality/pending-reversals", "get", 200);
        assertThat(pending.get("items")).isNotEmpty();
        call(get(svc("/api/quality/pending-reversals")).param("status", "NOPE"), "/api/quality/pending-reversals", "get", 400);
        String c = openNcr("2");
        call(postJson(svc(NCRS) + "/" + c + "/review", key(), null), ONE + "/review", "post", 200);
        call(postJson(svc(NCRS) + "/" + c + "/dispositions", key(), disposition("REWORK", "1", "件")), ONE + "/dispositions", "post", 201);
        String reportC = jdbc.queryForObject("select source_report_id from ncr where ncr_no = ?", String.class, c);
        handler.handle("DefectReversed", DefectEvents.id(), DefectEvents.reversed(DefectEvents.id(), reportC, "REV-C"));
        JsonNode conflicts = call(get(svc("/api/quality/conflicts")), "/api/quality/conflicts", "get", 200);
        assertThat(conflicts.get("items").get(0).get("ncrNo").asText()).isEqualTo(c);
        call(get(svc("/api/quality/conflicts")).param("page", "-1"), "/api/quality/conflicts", "get", 400);

        // 401：无令牌；403：只读令牌调用写接口、无权限令牌调用查询。
        call(get(svc(NCRS)), null, NCRS, "get", 401);
        call(postJson(base + "/review", key(), null), reader(), ONE + "/review", "post", 403);
        call(get(svc(NCRS) + "/" + a), jwt().jwt(j -> j.subject("nobody")), ONE, "get", 403);

        assertThat(covered).isEqualTo(OpenApiContract.problemTypes());
        assertThat(covered).hasSize(7);

        // outbox 事件全部符合契约中的事件 schema；SCRAP 只登记记录，事件中没有任何库存相关字段。
        publisher.publishBatch();
        assertThat(sender.sent()).isNotEmpty();
        for (RecordingEventSender.Sent s : sender.sent()) OpenApiContract.assertEventConforms(s.tag(), s.payload());
        assertThat(sender.sent()).extracting(RecordingEventSender.Sent::tag).contains("NcrOpened", "NcrDisposed", "NcrClosed", "NcrVoided");
    }

    /** 修改类接口结果计数：ok、replay 与 Problem type 的下划线写法。 */
    @Test void apiResultsAreCounted() throws Exception {
        String n = openNcr("1");
        double ok = api("ok"), notFound = api("ncr_not_found"), invalid = api("invalid_status_transition");
        call(postJson(svc(NCRS) + "/" + n + "/close", key(), null), ONE + "/close", "post", 409);
        call(postJson(svc(NCRS) + "/NCR-20990101-0001/review", key(), null), ONE + "/review", "post", 404);
        call(postJson(svc(NCRS) + "/" + n + "/review", key(), null), ONE + "/review", "post", 200);
        assertThat(api("ok")).isEqualTo(ok + 1);
        assertThat(api("ncr_not_found")).isEqualTo(notFound + 1);
        assertThat(api("invalid_status_transition")).isEqualTo(invalid + 1);
    }
}
