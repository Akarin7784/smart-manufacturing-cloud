package com.smartmanufacturing.inventory;

import com.fasterxml.jackson.databind.JsonNode;
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
import org.springframework.transaction.support.TransactionTemplate;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * 预留接口契约测试（H2）：每个响应（成功与错误）都按 docs/api/inventory-openapi.yaml 校验状态码、媒体类型与响应体 schema，
 * 并断言 9 种 ProblemType 全部被触发且 type 与状态码匹配。工单侧替身应按同一契约生成，两边以本测试为共同基线。
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class ReservationContractTest {
    private static final String BASE = "/api/inventory/reservations";
    private static final String ONE = BASE + "/{reservationId}";

    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired TransactionTemplate tx;
    @Autowired StockService stock;
    @Autowired LocationService locations;
    @Autowired ReservationExpiryJob expiry;
    @Autowired io.micrometer.core.instrument.MeterRegistry registry;

    /** 已触发的 ProblemType，最终必须覆盖契约中的全部取值。 */
    private final Set<String> covered = new TreeSet<>();

    /** 工单服务的服务身份令牌：aud=inventory-service，scope=inventory:reserve，client_id=workorder-service。 */
    private static RequestPostProcessor service() {
        return jwt().jwt(j -> j.subject("workorder-service").claim("client_id", "workorder-service").audience(List.of("inventory-service")))
            .authorities(new SimpleGrantedAuthority("SCOPE_inventory:reserve"));
    }

    @BeforeEach void stockUp() {
        if (jdbc.queryForObject("select count(*) from warehouse_location where code = 'CT-01'", Integer.class) == 0)
            locations.create(new InventoryDtos.CreateLocation("CT-01", "WH", "契约测试库位"));
    }

    private void inbound(String material, String uom, String lot, String qty) {
        tx.executeWithoutResult(s -> stock.inbound("ct", new InventoryDtos.Movement(material, uom, "CT-01", lot, qty, null)));
    }

    /** 发送请求，按契约校验响应；若为 problem+json 则记录 type 并核对 status 字段与 HTTP 状态一致。 */
    private JsonNode call(MockHttpServletRequestBuilder req, String template, String method, int expectedStatus) throws Exception {
        MvcResult r = mvc.perform(req.with(service())).andReturn();
        assertThat(r.getResponse().getStatus()).as(method + " " + template + "：" + r.getResponse().getContentAsString()).isEqualTo(expectedStatus);
        JsonNode body = OpenApiContract.assertConforms(template, method, r);
        if (expectedStatus >= 400 && body.has("type") && !"about:blank".equals(body.get("type").asText())) {
            covered.add(body.get("type").asText());
            assertThat(body.get("status").asInt()).isEqualTo(expectedStatus);
        }
        return body;
    }
    private static MockHttpServletRequestBuilder postJson(String url, String key, String body) {
        MockHttpServletRequestBuilder b = post(url).contentType(MediaType.APPLICATION_JSON);
        if (key != null) b.header("Idempotency-Key", key);
        return body == null ? b : b.content(body);
    }
    private static String createBody(String wo, String... lines) {
        return "{\"workOrderNo\":\"" + wo + "\",\"lines\":[" + String.join(",", lines) + "]}";
    }
    private static String line(String material, String qty, String uom, String source) {
        return "{\"materialCode\":\"" + material + "\",\"qty\":\"" + qty + "\",\"uom\":\"" + uom + "\",\"sourceLineId\":\"" + source + "\"}";
    }
    private static String key() { return UUID.randomUUID().toString(); }
    private static String svc(String path) { return path.replaceFirst("^/api", ""); }

    /** 完整生命周期 + 全部错误类型：每一步都按契约校验。 */
    @Test void everyResponseConformsAndAllProblemTypesAreCovered() throws Exception {
        inbound("CT-A", "kg", "LA", "100");
        inbound("CT-B", "个", "LB", "10");

        // validation-error：sourceLineId 重复（400）。
        call(postJson(svc(BASE), key(), createBody("WO-CT-0", line("CT-A", "1", "kg", "S1"), line("CT-A", "1", "kg", "S1"))), BASE, "post", 400);
        // uom-mismatch：CT-A 已按 kg 登记现存量，用“件”预留返回 422，且不产生预留。
        JsonNode mismatch = call(postJson(svc(BASE), key(), createBody("WO-CT-U", line("CT-A", "1", "件", "WO-CT-U:10"))), BASE, "post", 422);
        assertThat(mismatch.get("type").asText()).isEqualTo("uom-mismatch");
        assertThat(jdbc.queryForObject("select count(*) from reservation where work_order_no = 'WO-CT-U'", Integer.class)).isZero();
        // insufficient-stock：按物料 + 单位汇总，列出全部缺口与 sourceLineIds（409）。
        JsonNode shortage = call(postJson(svc(BASE), key(), createBody("WO-CT-0",
            line("CT-B", "8", "个", "S1"), line("CT-B", "5", "个", "S2"), line("CT-NONE", "1", "kg", "S3"))), BASE, "post", 409);
        assertThat(shortage.get("shortages")).hasSize(2);
        // 从未入库的物料：按可用量 0 计入缺口，uom 取请求单位，不返回 uom-mismatch。
        JsonNode none = shortage.get("shortages").get(1);
        assertThat(none.get("materialCode").asText()).isEqualTo("CT-NONE");
        assertThat(none.get("uom").asText()).isEqualTo("kg");
        assertThat(none.get("available").asText()).isEqualTo("0");
        assertThat(none.get("shortage").asText()).isEqualTo("1");
        assertThat(shortage.get("shortages").get(0).get("sourceLineIds")).hasSize(2);

        // 成功创建（201），同键重放原样返回首次状态码与响应体。
        String k = key();
        String body = createBody("WO-CT-1", line("CT-A", "10.5", "kg", "WO-CT-1:10"), line("CT-B", "2", "个", "WO-CT-1:20"));
        MvcResult first = mvc.perform(postJson(svc(BASE), k, body).with(service())).andReturn();
        JsonNode created = OpenApiContract.assertConforms(BASE, "post", first);
        assertThat(first.getResponse().getStatus()).isEqualTo(201);
        assertThat(first.getResponse().getHeader("Location")).endsWith(created.get("reservationId").asText());
        MvcResult replay = mvc.perform(postJson(svc(BASE), k, body).with(service())).andReturn();
        assertThat(replay.getResponse().getStatus()).isEqualTo(201);
        assertThat(replay.getResponse().getContentAsString()).isEqualTo(first.getResponse().getContentAsString());
        // idempotency-key-conflict：同键不同内容（422）。
        call(postJson(svc(BASE), k, createBody("WO-CT-1", line("CT-A", "11", "kg", "WO-CT-1:10"))), BASE, "post", 422);

        String id = created.get("reservationId").asText();
        String lineA = created.get("lines").get(0).get("lineId").asText();
        String lineB = created.get("lines").get(1).get("lineId").asText();
        String consumeTpl = ONE + "/lines/{lineId}/consume";
        String consumeA = svc(BASE) + "/" + id + "/lines/" + lineA + "/consume";
        String consumeB = svc(BASE) + "/" + id + "/lines/" + lineB + "/consume";

        call(get(svc(BASE) + "/" + id), ONE, "get", 200);
        call(get(svc(BASE) + "/no-such"), ONE, "get", 404);
        call(postJson(svc(BASE) + "/" + id + "/renew", key(), null), ONE + "/renew", "post", 200);
        // over-consume：超过剩余可扣减量（422）。
        call(postJson(consumeB, key(), "{\"qty\":\"3\",\"lotNo\":\"LB\"}"), consumeTpl, "post", 422);
        // 扣完 B 行 → 行关闭；line-closed：再扣已关闭的行（409）。
        JsonNode afterB = call(postJson(consumeB, key(), "{\"qty\":\"2\",\"lotNo\":\"LB\"}"), consumeTpl, "post", 200);
        assertThat(afterB.get("lines").get(1).get("status").asText()).isEqualTo("CLOSED");
        call(postJson(consumeB, key(), "{\"qty\":\"1\",\"lotNo\":\"LB\"}"), consumeTpl, "post", 409);
        call(postJson(consumeA, key(), "{\"qty\":\"1\"}"), consumeTpl, "post", 400);
        // 扣完 A 行 → 整单 CONSUMED；reservation-consumed：释放已整单扣减的预留单（409）。
        JsonNode done = call(postJson(consumeA, key(), "{\"qty\":\"10.5\",\"lotNo\":\"LA\"}"), consumeTpl, "post", 200);
        assertThat(done.get("status").asText()).isEqualTo("CONSUMED");
        call(postJson(svc(BASE) + "/" + id + "/release", key(), null), ONE + "/release", "post", 409);
        call(postJson(svc(BASE) + "/" + id + "/renew", key(), null), ONE + "/renew", "post", 409);

        // reservation-released：释放后续期（410）；重复释放返回 200。
        JsonNode r2 = call(postJson(svc(BASE), key(), createBody("WO-CT-2", line("CT-A", "1", "kg", "WO-CT-2:10"))), BASE, "post", 201);
        String id2 = r2.get("reservationId").asText();
        JsonNode released = call(postJson(svc(BASE) + "/" + id2 + "/release", key(), "{\"reason\":\"COMPENSATION\"}"), ONE + "/release", "post", 200);
        assertThat(released.get("status").asText()).isEqualTo("RELEASED");
        call(postJson(svc(BASE) + "/" + id2 + "/release", key(), null), ONE + "/release", "post", 200);
        call(postJson(svc(BASE) + "/" + id2 + "/renew", key(), null), ONE + "/renew", "post", 410);

        // reservation-expired：到期后由过期任务判定，再扣减返回 410。
        JsonNode r3 = call(postJson(svc(BASE), key(), createBody("WO-CT-3", line("CT-A", "1", "kg", "WO-CT-3:10"))), BASE, "post", 201);
        String id3 = r3.get("reservationId").asText();
        jdbc.update("update reservation set expires_at = ? where id = ?", Timestamp.from(Instant.now().minusSeconds(1)), id3);
        assertThat(expiry.expireDue()).isGreaterThanOrEqualTo(1);
        call(postJson(svc(BASE) + "/" + id3 + "/lines/" + r3.get("lines").get(0).get("lineId").asText() + "/consume", key(),
            "{\"qty\":\"1\",\"lotNo\":\"LA\"}"), consumeTpl, "post", 410);
        call(postJson(svc(BASE) + "/" + id3 + "/renew", key(), null), ONE + "/renew", "post", 410);

        // 契约固定 9 种 type，全部被触发且与 ProblemType 枚举一致。
        assertThat(covered).isEqualTo(OpenApiContract.problemTypes()).hasSize(9);
        assertThat(covered).containsExactlyInAnyOrderElementsOf(java.util.Arrays.stream(ProblemType.values()).map(ProblemType::value).toList());

        // 指标：结果、状态迁移、出入库数量均已计数，且标签不含工单号/物料号。
        assertThat(registry.get("mes.inventory.reservation").tag("result", "ok").counter().count()).isGreaterThanOrEqualTo(3);
        assertThat(registry.get("mes.inventory.reservation").tag("result", "replay").counter().count()).isGreaterThanOrEqualTo(1);
        for (String result : List.of("insufficient_stock", "validation_error", "idempotency_key_conflict", "uom_mismatch"))
            assertThat(registry.get("mes.inventory.reservation").tag("result", result).counter().count()).as(result).isGreaterThanOrEqualTo(1);
        for (String to : List.of("ACTIVE", "RELEASED", "EXPIRED", "CONSUMED"))
            assertThat(registry.get("mes.inventory.reservation.transition").tag("to", to).counter().count()).as(to).isGreaterThanOrEqualTo(1);
        assertThat(registry.get("mes.inventory.movement.qty").tag("kind", "consume").tag("uom", "kg").counter().count()).isGreaterThanOrEqualTo(10.5);
        assertThat(registry.get("mes.inventory.outbox.pending").gauge().value()).isGreaterThanOrEqualTo(1);
        assertThat(registry.get("mes.inventory.stock.lock.wait").timer().count()).isPositive();
        registry.getMeters().stream().filter(m -> m.getId().getName().startsWith("mes.inventory"))
            .flatMap(m -> m.getId().getTags().stream())
            // application 为 #21 引入的全局公共标签（取服务名，低基数），其余只允许业务低基数标签。
            .forEach(t -> assertThat(t.getKey()).isIn("application", "result", "to", "kind", "uom"));
    }

    /** 用户令牌（aud=manufacturing-api，即使带 inventory 权限）调用预留接口：403，响应符合契约。 */
    @Test void userTokenIsForbiddenOnReservations() throws Exception {
        MvcResult r = mvc.perform(postJson(svc(BASE), key(), createBody("WO-SEC-1", line("CT-A", "1", "kg", "WO-SEC-1:10")))
            .with(jwt().jwt(j -> j.subject("keeper").audience(List.of("manufacturing-api")))
                .authorities(new SimpleGrantedAuthority("inventory:read"), new SimpleGrantedAuthority("inventory:write")))).andReturn();
        assertThat(r.getResponse().getStatus()).isEqualTo(403);
        OpenApiContract.assertConforms(BASE, "post", r);
    }

    /** 服务令牌受众正确但缺少 inventory:reserve scope：403，且不产生预留。 */
    @Test void serviceTokenWithoutReserveScopeIsForbidden() throws Exception {
        MvcResult r = mvc.perform(postJson(svc(BASE), key(), createBody("WO-SEC-2", line("CT-A", "1", "kg", "WO-SEC-2:10")))
            .with(jwt().jwt(j -> j.subject("other-service").claim("client_id", "other-service").audience(List.of("inventory-service")))
                .authorities(new SimpleGrantedAuthority("SCOPE_inventory:read")))).andReturn();
        assertThat(r.getResponse().getStatus()).isEqualTo(403);
        OpenApiContract.assertConforms(BASE, "post", r);
        assertThat(jdbc.queryForObject("select count(*) from reservation where work_order_no = 'WO-SEC-2'", Integer.class)).isZero();
    }

    /** 只有持有该预留的调用方能续期：其他服务身份（同样具备 inventory:reserve）续期返回 403，expiresAt 不变。 */
    @Test void onlyHolderCanRenew() throws Exception {
        inbound("CT-R", "kg", "LR", "5");
        JsonNode created = call(postJson(svc(BASE), key(), createBody("WO-CT-R", line("CT-R", "1", "kg", "WO-CT-R:10"))), BASE, "post", 201);
        String id = created.get("reservationId").asText();
        MvcResult r = mvc.perform(postJson(svc(BASE) + "/" + id + "/renew", key(), null)
            .with(jwt().jwt(j -> j.subject("other-service").claim("client_id", "other-service").audience(List.of("inventory-service")))
                .authorities(new SimpleGrantedAuthority("SCOPE_inventory:reserve")))).andReturn();
        assertThat(r.getResponse().getStatus()).isEqualTo(403);
        OpenApiContract.assertConforms(ONE + "/renew", "post", r);
        assertThat(call(get(svc(BASE) + "/" + id), ONE, "get", 200).get("expiresAt").asText()).isEqualTo(created.get("expiresAt").asText());
        // 持有方续期成功。
        call(postJson(svc(BASE) + "/" + id + "/renew", key(), null), ONE + "/renew", "post", 200);
    }
}
