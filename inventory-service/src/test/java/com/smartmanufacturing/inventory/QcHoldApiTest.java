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
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import java.util.List;
import java.util.UUID;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * 质检隔离库位 QC-HOLD 接口测试（H2）：授权边界、严格请求体、releases 的 dispositionId 规则、幂等重放、
 * 隔离量不计可用/不可预留、通用出入库拒绝 QC-HOLD；所有响应按 inventory-openapi.yaml 校验。
 * MockMvc 的 jwt() 跳过解码器，aud 不符在此表现为授权规则拒绝（403）；解码阶段的 401 由 MySQL IT 之外的安全配置保证。
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class QcHoldApiTest {
    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;

    /** 质检服务令牌：aud=inventory-service，scope inventory:qc-hold。 */
    private static RequestPostProcessor quality() {
        return jwt().jwt(j -> j.subject("quality-service").claim("client_id", "quality-service").audience(List.of("inventory-service")))
            .authorities(new SimpleGrantedAuthority("SCOPE_inventory:qc-hold"));
    }
    /** 工单服务令牌：只有 inventory:reserve，不能调用隔离接口。 */
    private static RequestPostProcessor workorder() {
        return jwt().jwt(j -> j.subject("workorder-service").claim("client_id", "workorder-service").audience(List.of("inventory-service")))
            .authorities(new SimpleGrantedAuthority("SCOPE_inventory:reserve"));
    }
    /** 仓管员用户令牌。 */
    private static RequestPostProcessor keeper() {
        return jwt().jwt(j -> j.subject("keeper").audience(List.of("manufacturing-api")))
            .authorities(new SimpleGrantedAuthority("inventory:read"), new SimpleGrantedAuthority("inventory:write"));
    }

    private MvcResult call(String op, String key, String body, RequestPostProcessor who, int expected) throws Exception {
        var req = post("/inventory/qc-hold/" + op).with(who).contentType(MediaType.APPLICATION_JSON).content(body);
        if (key != null) req.header("Idempotency-Key", key);
        MvcResult r = mvc.perform(req).andExpect(status().is(expected)).andReturn();
        OpenApiContract.assertConforms("/inventory/qc-hold/" + op, "post", r);
        return r;
    }
    private static String base(String material, String qty) {
        return "\"ncrNo\":\"NCR-20261012-0001\",\"materialCode\":\"" + material + "\",\"uom\":\"pcs\",\"lotNo\":\"B1\",\"qty\":\"" + qty + "\"";
    }
    private static String key() { return "qc:" + UUID.randomUUID(); }

    /** 入隔离 → 可用量不变、隔离量增加；报废与放行扣减隔离量；流水类型与 refType 正确。 */
    @Test void holdScrapReleaseFlowKeepsQuarantineSeparate() throws Exception {
        call("receipts", key(), "{" + base("QH-A", "3.000") + "}", quality(), 201);
        mvc.perform(get("/inventory/stock/availability").param("materialCode", "QH-A").with(keeper()))
            .andExpect(jsonPath("$.onHand").value("0")).andExpect(jsonPath("$.available").value("0")).andExpect(jsonPath("$.quarantined").value("3"));
        mvc.perform(get("/inventory/stock").param("materialCode", "QH-A").with(keeper()))
            .andExpect(jsonPath("$[0].locationCode").value("QC-HOLD")).andExpect(jsonPath("$[0].inQcHold").value(true));
        call("scraps", key(), "{" + base("QH-A", "1") + ",\"dispositionId\":7}", quality(), 201);
        MvcResult rel = call("releases", key(), "{" + base("QH-A", "1") + ",\"reason\":\"REWORK\",\"dispositionId\":8}", quality(), 201);
        assertThat(rel.getResponse().getContentAsString()).contains("\"txnType\":\"QC_HOLD_RELEASE\"").contains("\"refType\":\"NCR\"")
            .contains("\"operator\":\"quality-service\"");
        call("releases", key(), "{" + base("QH-A", "1") + ",\"reason\":\"NCR_VOIDED\"}", quality(), 201);
        // 余额已为 0，再出隔离 → 409 insufficient-stock。
        call("scraps", key(), "{" + base("QH-A", "1") + ",\"dispositionId\":9}", quality(), 409);
        assertThat(jdbc.queryForList("select txn_type from stock_txn where material_code = 'QH-A' order by id", String.class))
            .containsExactly("QC_HOLD_IN", "SCRAP", "QC_HOLD_RELEASE", "QC_HOLD_RELEASE");
    }

    /** 同键同请求重放首次响应；同键不同请求 422；缺键 400。 */
    @Test void idempotencyReusesExistingMechanism() throws Exception {
        String k = key();
        String first = call("receipts", k, "{" + base("QH-I", "2") + "}", quality(), 201).getResponse().getContentAsString();
        MvcResult replay = call("receipts", k, "{" + base("QH-I", "2.0") + "}", quality(), 201);
        assertThat(replay.getResponse().getContentAsString()).isEqualTo(first);
        assertThat(replay.getResponse().getHeader("Idempotent-Replayed")).isEqualTo("true");
        call("receipts", k, "{" + base("QH-I", "5") + "}", quality(), 422);
        call("receipts", null, "{" + base("QH-I", "2") + "}", quality(), 400);
        assertThat(jdbc.queryForObject("select qty from stock_on_hand where material_code = 'QH-I'", java.math.BigDecimal.class)).isEqualByComparingTo("2");
    }

    /** 请求体严格校验：带库位等多余字段 400；releases 的 reason 与 dispositionId 组合不符 400；scraps 缺 dispositionId 400。 */
    @Test void strictBodiesAndDispositionRule() throws Exception {
        call("receipts", key(), "{" + base("QH-V", "1") + ",\"locationCode\":\"RM-A-01\"}", quality(), 400);
        call("releases", key(), "{" + base("QH-V", "1") + ",\"reason\":\"REWORK\"}", quality(), 400);
        call("releases", key(), "{" + base("QH-V", "1") + ",\"reason\":\"CONCESSION\"}", quality(), 400);
        call("releases", key(), "{" + base("QH-V", "1") + ",\"reason\":\"NCR_VOIDED\",\"dispositionId\":1}", quality(), 400);
        call("releases", key(), "{" + base("QH-V", "1") + ",\"reason\":\"OTHER\",\"dispositionId\":1}", quality(), 400);
        call("scraps", key(), "{" + base("QH-V", "1") + "}", quality(), 400);
        call("receipts", key(), "{" + base("QH-V", "0") + "}", quality(), 400);
    }

    /** 授权：预留 scope 与用户令牌都不能调用隔离接口（403），未认证 401。 */
    @Test void onlyQcHoldScopeIsAccepted() throws Exception {
        call("receipts", key(), "{" + base("QH-S", "1") + "}", workorder(), 403);
        call("receipts", key(), "{" + base("QH-S", "1") + "}", keeper(), 403);
        mvc.perform(post("/inventory/qc-hold/receipts").contentType(MediaType.APPLICATION_JSON).content("{}"))
            .andExpect(status().isUnauthorized()).andExpect(content().contentTypeCompatibleWith("application/problem+json"));
        // 质检令牌也不能调用通用出入库（用户链按 permissions 授权，服务令牌没有 inventory:write）。
        mvc.perform(post("/inventory/inbound").with(quality()).header("Idempotency-Key", key()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"materialCode\":\"QH-S\",\"uom\":\"pcs\",\"locationCode\":\"QC-HOLD\",\"lotNo\":\"B1\",\"qty\":\"1\"}"))
            .andExpect(status().isForbidden());
    }

    /** 通用入库/出库对 QC-HOLD 返回 400；只有隔离量时预留与通用出库按可用量 0 处理（409）。 */
    @Test void generalMovementsAndReservationsCannotTouchQcHold() throws Exception {
        String inbound = "{\"materialCode\":\"QH-G\",\"uom\":\"pcs\",\"locationCode\":\"QC-HOLD\",\"lotNo\":\"B1\",\"qty\":\"1\"}";
        mvc.perform(post("/inventory/inbound").with(keeper()).header("Idempotency-Key", key()).contentType(MediaType.APPLICATION_JSON).content(inbound))
            .andExpect(status().isBadRequest()).andExpect(jsonPath("$.type").value(org.hamcrest.Matchers.endsWith("validation-error")));
        call("receipts", key(), "{" + base("QH-G", "5") + "}", quality(), 201);
        mvc.perform(post("/inventory/outbound").with(keeper()).header("Idempotency-Key", key()).contentType(MediaType.APPLICATION_JSON).content(inbound))
            .andExpect(status().isBadRequest());
        String reserve = "{\"workOrderNo\":\"WO-QH-1\",\"lines\":[{\"sourceLineId\":\"S1\",\"materialCode\":\"QH-G\",\"qty\":\"1\",\"uom\":\"pcs\"}]}";
        mvc.perform(post("/inventory/reservations").with(workorder()).header("Idempotency-Key", key()).contentType(MediaType.APPLICATION_JSON).content(reserve))
            .andExpect(status().isConflict());
    }
}
