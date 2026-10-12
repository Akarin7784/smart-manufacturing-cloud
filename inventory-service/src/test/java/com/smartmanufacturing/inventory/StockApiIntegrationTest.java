package com.smartmanufacturing.inventory;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * 库存基础接口测试（H2，不加载演示数据）：保护出入库幂等与重放语义、可用量校验、计量单位一致性、
 * RFC 7807 错误格式与两类令牌的授权边界。每个用例使用独立物料编码，避免共享内存库的用例互相影响。
 */
@SpringBootTest(properties = "inventory.idempotency.cleanup.enabled=true")
@AutoConfigureMockMvc
@ActiveProfiles("test")
class StockApiIntegrationTest {
    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired IdempotencyRecordCleaner cleaner;

    /** 仓管员：读写库存。 */
    private static RequestPostProcessor keeper() {
        return jwt().jwt(j -> j.subject("keeper")).authorities(new SimpleGrantedAuthority("inventory:read"), new SimpleGrantedAuthority("inventory:write"));
    }
    private static RequestPostProcessor viewer() { return jwt().authorities(new SimpleGrantedAuthority("inventory:read")); }

    private ResultActions send(String url, String key, String body, RequestPostProcessor who) throws Exception {
        var req = post(url).with(who).contentType(MediaType.APPLICATION_JSON).content(body);
        if (key != null) req.header("Idempotency-Key", key);
        return mvc.perform(req);
    }
    private static String movement(String material, String uom, String location, String lot, String qty) {
        return "{\"materialCode\":\"" + material + "\",\"uom\":\"" + uom + "\",\"locationCode\":\"" + location + "\",\"lotNo\":\"" + lot + "\",\"qty\":\"" + qty + "\",\"refNo\":\"PO-1\"}";
    }
    private void location(String code) throws Exception {
        send("/inventory/locations", null, "{\"code\":\"" + code + "\",\"warehouseCode\":\"WH\",\"name\":\"测试库位\"}", keeper()).andExpect(status().isCreated());
    }
    private static String key() { return UUID.randomUUID().toString(); }

    /** 入库成功写余额与流水；同键同内容重放返回首次状态码与响应体原文，且不重复入库。 */
    @Test void inboundIsIdempotentAndReplaysFirstResponseVerbatim() throws Exception {
        location("T-IN-01");
        String k = key();
        String first = send("/inventory/inbound", k, movement("M-IN", "kg", "T-IN-01", "L1", "10.5"), keeper())
            .andExpect(status().isCreated()).andExpect(header().string("Idempotent-Replayed", "false"))
            .andExpect(jsonPath("$.qty").value("10.5")).andExpect(jsonPath("$.balanceAfter").value("10.5"))
            .andExpect(jsonPath("$.operator").value("keeper")).andReturn().getResponse().getContentAsString();
        // 数量写法不同（10.500）但语义相同，规范化后摘要一致，按重放处理。
        String replay = send("/inventory/inbound", k, movement("M-IN", "kg", "T-IN-01", "L1", "10.500"), keeper())
            .andExpect(status().isCreated()).andExpect(header().string("Idempotent-Replayed", "true"))
            .andReturn().getResponse().getContentAsString();
        assertThat(replay).isEqualTo(first);
        mvc.perform(get("/inventory/stock").param("materialCode", "M-IN").with(viewer()))
            .andExpect(status().isOk()).andExpect(jsonPath("$[0].qty").value("10.5")).andExpect(jsonPath("$[0].version").value(1));
        mvc.perform(get("/inventory/ledger").param("materialCode", "M-IN").with(viewer()))
            .andExpect(jsonPath("$", hasSize(1))).andExpect(jsonPath("$[0].txnType").value("INBOUND"));
        mvc.perform(get("/inventory/lots").param("materialCode", "M-IN").with(viewer())).andExpect(jsonPath("$[0].lotNo").value("L1"));
    }

    /** 同键不同内容返回 422 idempotency-key-conflict；缺少幂等键返回 400 validation-error，都是 problem+json。 */
    @Test void idempotencyKeyMisuseIsRejected() throws Exception {
        location("T-IK-01");
        String k = key();
        send("/inventory/inbound", k, movement("M-IK", "kg", "T-IK-01", "L1", "1"), keeper()).andExpect(status().isCreated());
        send("/inventory/inbound", k, movement("M-IK", "kg", "T-IK-01", "L1", "2"), keeper())
            .andExpect(status().isUnprocessableEntity()).andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
            .andExpect(jsonPath("$.type").value("idempotency-key-conflict")).andExpect(jsonPath("$.status").value(422));
        // 同一个键挪用到出库操作，也视为冲突。
        send("/inventory/outbound", k, movement("M-IK", "kg", "T-IK-01", "L1", "1"), keeper())
            .andExpect(status().isUnprocessableEntity()).andExpect(jsonPath("$.type").value("idempotency-key-conflict"));
        send("/inventory/inbound", null, movement("M-IK", "kg", "T-IK-01", "L1", "1"), keeper())
            .andExpect(status().isBadRequest()).andExpect(jsonPath("$.type").value("validation-error"));
    }

    /** 出库不得使余额为负，也不得侵占 ACTIVE 预留；不足时 409 insufficient-stock 给出缺口。 */
    @Test void outboundChecksBalanceAndActiveReservations() throws Exception {
        location("T-OUT-01");
        send("/inventory/inbound", key(), movement("M-OUT", "件", "T-OUT-01", "L1", "10"), keeper()).andExpect(status().isCreated());
        send("/inventory/outbound", key(), movement("M-OUT", "件", "T-OUT-01", "L1", "11"), keeper())
            .andExpect(status().isConflict()).andExpect(jsonPath("$.type").value("insufficient-stock"))
            .andExpect(jsonPath("$.shortages[0].requested").value("11")).andExpect(jsonPath("$.shortages[0].available").value("10"))
            .andExpect(jsonPath("$.shortages[0].shortage").value("1")).andExpect(jsonPath("$.shortages[0].uom").value("件"));
        // 模拟一张 ACTIVE 预留占用 7：可出库量降为 3。
        String rid = UUID.randomUUID().toString();
        Timestamp now = Timestamp.from(Instant.now());
        jdbc.update("insert into reservation (id, work_order_no, status, expires_at, principal, created_at, updated_at) values (?, 'WO-1', 'ACTIVE', ?, 'wo', ?, ?)",
            rid, Timestamp.from(Instant.now().plus(15, ChronoUnit.MINUTES)), now, now);
        jdbc.update("insert into reservation_line (id, reservation_id, line_no, source_line_id, material_code, uom, qty, reserved_qty, consumed_qty, status)"
            + " values (?, ?, 1, 'WO-1:1', 'M-OUT', '件', 7, 7, 0, 'OPEN')", UUID.randomUUID().toString(), rid);
        mvc.perform(get("/inventory/stock/availability").param("materialCode", "M-OUT").with(viewer()))
            .andExpect(jsonPath("$.onHand").value("10")).andExpect(jsonPath("$.reserved").value("7")).andExpect(jsonPath("$.available").value("3"));
        send("/inventory/outbound", key(), movement("M-OUT", "件", "T-OUT-01", "L1", "4"), keeper())
            .andExpect(status().isConflict()).andExpect(jsonPath("$.shortages[0].available").value("3"));
        send("/inventory/outbound", key(), movement("M-OUT", "件", "T-OUT-01", "L1", "3"), keeper())
            .andExpect(status().isCreated()).andExpect(jsonPath("$.qty").value("-3")).andExpect(jsonPath("$.balanceAfter").value("7"));
    }

    /** 入库单位与该物料已登记的现存量单位不一致：422 uom-mismatch（不是 400），且不写余额与流水。 */
    @Test void inboundUomMismatchIs422() throws Exception {
        location("T-UOM-01");
        send("/inventory/inbound", key(), movement("M-UOM", "kg", "T-UOM-01", "L1", "1"), keeper()).andExpect(status().isCreated());
        send("/inventory/inbound", key(), movement("M-UOM", "件", "T-UOM-01", "L1", "1"), keeper())
            .andExpect(status().isUnprocessableEntity())
            .andExpect(content().contentTypeCompatibleWith("application/problem+json"))
            .andExpect(jsonPath("$.type").value("uom-mismatch")).andExpect(jsonPath("$.detail", containsString("kg")));
        // 被拒的请求不能改动余额：现存量仍为首次入库的 1。
        assertThat(jdbc.queryForObject("select sum(qty) from stock_on_hand where material_code = 'M-UOM'", java.math.BigDecimal.class))
            .isEqualByComparingTo("1");
    }

    /** 不存在或停用的库位、非法数量、缺字段都是 400 validation-error（validation-error 只对应 400）。 */
    @Test void validationErrors() throws Exception {
        location("T-VAL-01");
        send("/inventory/inbound", key(), movement("M-VAL", "kg", "T-VAL-01", "L1", "1"), keeper()).andExpect(status().isCreated());
        send("/inventory/inbound", key(), movement("M-VAL", "kg", "NO-SUCH", "L1", "1"), keeper())
            .andExpect(status().isBadRequest()).andExpect(jsonPath("$.type").value("validation-error"));
        send("/inventory/inbound", key(), movement("M-VAL", "kg", "T-VAL-01", "L1", "1.0000001"), keeper())
            .andExpect(status().isBadRequest()).andExpect(jsonPath("$.type").value("validation-error"));
        send("/inventory/inbound", key(), "{\"materialCode\":\"M-VAL\"}", keeper())
            .andExpect(status().isBadRequest()).andExpect(jsonPath("$.type").value("validation-error"));
        send("/inventory/locations", null, "{\"code\":\"T-VAL-01\",\"warehouseCode\":\"WH\",\"name\":\"重复\"}", keeper())
            .andExpect(status().isBadRequest()).andExpect(jsonPath("$.type").value("validation-error"));
        mvc.perform(get("/inventory/stock/availability").param("materialCode", "NO-SUCH").with(viewer()))
            .andExpect(status().isNotFound()).andExpect(jsonPath("$.type").value("about:blank"));
    }

    /** 授权边界：未认证 401、只读用户写入 403、用户令牌访问预留接口 403、受众或 scope 不符的服务令牌 403，均为 problem+json。 */
    @Test void authorizationBoundaries() throws Exception {
        mvc.perform(get("/inventory/locations")).andExpect(status().isUnauthorized())
            .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON));
        send("/inventory/inbound", key(), movement("M-AUTH", "kg", "X", "L1", "1"), viewer())
            .andExpect(status().isForbidden()).andExpect(jsonPath("$.status").value(403));
        mvc.perform(get("/inventory/locations").with(jwt())).andExpect(status().isForbidden());
        mvc.perform(get("/inventory/reservations/any").with(keeper())).andExpect(status().isForbidden());
        mvc.perform(get("/inventory/reservations/any").with(jwt().jwt(j -> j.audience(java.util.List.of("manufacturing-api")))
            .authorities(new SimpleGrantedAuthority("SCOPE_inventory:reserve")))).andExpect(status().isForbidden());
        mvc.perform(get("/inventory/reservations/any").with(jwt().jwt(j -> j.audience(java.util.List.of("inventory-service")))
            .authorities(new SimpleGrantedAuthority("SCOPE_other")))).andExpect(status().isForbidden());
    }

    /** 服务令牌按 client_id 划分幂等作用域：两个调用方用相同的键互不影响。 */
    @Test void idempotencyScopedByCaller() throws Exception {
        location("T-SC-01");
        String k = key();
        RequestPostProcessor a = jwt().jwt(j -> j.subject("u").claim("client_id", "svc-a")).authorities(new SimpleGrantedAuthority("inventory:write"), new SimpleGrantedAuthority("inventory:read"));
        RequestPostProcessor b = jwt().jwt(j -> j.subject("u").claim("client_id", "svc-b")).authorities(new SimpleGrantedAuthority("inventory:write"), new SimpleGrantedAuthority("inventory:read"));
        send("/inventory/inbound", k, movement("M-SC", "kg", "T-SC-01", "L1", "1"), a).andExpect(status().isCreated()).andExpect(jsonPath("$.operator").value("svc-a"));
        send("/inventory/inbound", k, movement("M-SC", "kg", "T-SC-01", "L1", "2"), b).andExpect(status().isCreated()).andExpect(header().string("Idempotent-Replayed", "false"));
        assertThat(jdbc.queryForObject("select qty from stock_on_hand where material_code = 'M-SC'", java.math.BigDecimal.class)).isEqualByComparingTo("3");
    }

    /** 幂等记录清理：只删除早于 7 天保留期的记录，不影响业务数据。 */
    @Test void cleanerPurgesOnlyExpiredRecords() {
        jdbc.update("insert into idempotency_record (principal, idempotency_key, operation, request_hash, response_status, response_body, created_at)"
            + " values ('p', 'old', 'inbound', ?, 201, '{}', ?)", "0".repeat(64), Timestamp.from(Instant.now().minus(8, ChronoUnit.DAYS)));
        jdbc.update("insert into idempotency_record (principal, idempotency_key, operation, request_hash, response_status, response_body, created_at)"
            + " values ('p', 'new', 'inbound', ?, 201, '{}', ?)", "0".repeat(64), Timestamp.from(Instant.now().minus(1, ChronoUnit.DAYS)));
        assertThat(cleaner.purgeExpired()).isGreaterThanOrEqualTo(1);
        assertThat(jdbc.queryForList("select idempotency_key from idempotency_record where principal = 'p'", String.class)).containsExactly("new");
    }
}
