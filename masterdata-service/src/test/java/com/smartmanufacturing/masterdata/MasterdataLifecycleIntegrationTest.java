package com.smartmanufacturing.masterdata;

import com.smartmanufacturing.api.ApiResponse;
import com.smartmanufacturing.api.workorder.WorkOrderReferenceView;
import feign.FeignException;
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
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import java.util.List;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * M1 PR2 主数据收口（H2）：工作中心 / 工位修改与软删除（默认列表隐藏）、BOM / 工艺路线作废与删除草稿、
 * 工艺路线工序仅草稿可改，以及三类引用检查（WORK_ORDER / BOM / ROUTING）返回 409 MASTERDATA_IN_USE（data.type = masterdata-in-use）。
 * 工单服务引用检查以 Mock 替代；每个用例使用独立编码。
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class MasterdataLifecycleIntegrationTest {
    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @MockitoBean org.springframework.security.oauth2.jwt.JwtDecoder decoder;
    @MockitoBean WorkOrderReferenceClient workOrders;

    @BeforeEach void noWorkOrders() {
        when(workOrders.references(any(), any())).thenReturn(ApiResponse.ok(new WorkOrderReferenceView(0, List.of())));
        when(workOrders.versionReferences(any(), any(), any())).thenReturn(ApiResponse.ok(new WorkOrderReferenceView(0, List.of())));
    }

    private static RequestPostProcessor admin() {
        return jwt().authorities(new SimpleGrantedAuthority("masterdata:read"), new SimpleGrantedAuthority("masterdata:write"),
            new SimpleGrantedAuthority("masterdata:release"));
    }
    private static RequestPostProcessor writer() {
        return jwt().authorities(new SimpleGrantedAuthority("masterdata:read"), new SimpleGrantedAuthority("masterdata:write"));
    }
    private static RequestPostProcessor viewer() { return jwt().authorities(new SimpleGrantedAuthority("masterdata:read")); }

    private ResultActions post(String url, String body, RequestPostProcessor who) throws Exception {
        return mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post(url).with(who).contentType(MediaType.APPLICATION_JSON).content(body));
    }
    private ResultActions putJson(String url, String body, RequestPostProcessor who) throws Exception {
        return mvc.perform(put(url).with(who).contentType(MediaType.APPLICATION_JSON).content(body));
    }
    private ResultActions del(String url, RequestPostProcessor who) throws Exception { return mvc.perform(delete(url).with(who)); }

    private void material(String code, String type, String source) throws Exception {
        post("/materials", "{\"code\":\"" + code + "\",\"name\":\"" + code + "\",\"type\":\"" + type + "\",\"uom\":\"件\",\"traceMode\":\"LOT\",\"source\":\"" + source + "\"}", admin())
            .andExpect(status().isCreated());
    }
    private void workCenter(String code, String... stations) throws Exception {
        post("/workcenters", "{\"code\":\"" + code + "\",\"name\":\"中心\",\"workshop\":\"W01\",\"outsourced\":false}", admin()).andExpect(status().isCreated());
        for (String s : stations) post("/workcenters/" + code + "/stations", "{\"code\":\"" + s + "\",\"name\":\"工位\"}", admin()).andExpect(status().isCreated());
    }
    private static String bomBody(String product, String version, String from, String component) {
        return "{\"productCode\":\"" + product + "\",\"version\":\"" + version + "\",\"effectiveFrom\":\"" + from + "\",\"lines\":[{\"lineNo\":10,\"componentCode\":\""
            + component + "\",\"qtyPer\":2,\"uom\":\"件\",\"scrapRate\":0,\"issueMode\":\"PICK\",\"phantom\":false}]}";
    }
    private static String routingBody(String product, String version, String from, String wc, String opName) {
        return "{\"productCode\":\"" + product + "\",\"version\":\"" + version + "\",\"effectiveFrom\":\"" + from + "\",\"operations\":["
            + "{\"opNo\":\"OP10\",\"name\":\"" + opName + "\",\"workCenterCode\":\"" + wc + "\",\"setupMinutes\":0,\"runSecondsPerUnit\":30,\"reportPoint\":true,\"firstArticleRequired\":false,\"inspectionPointCodes\":[]}]}";
    }
    private static String materialUpdate(boolean active, long version) {
        return "{\"name\":\"改名\",\"uom\":\"件\",\"traceMode\":\"LOT\",\"active\":" + active + ",\"expectedVersion\":" + version + "}";
    }

    // ------------------------------------------------------------------ 工作中心 / 工位

    /** 修改工作中心与工位；软删除后默认列表与单查都不可见，includeDeleted=true 可见并带 deleted 标志；不能再挂新工位或被新工艺路线发布引用。 */
    @Test void updateAndSoftDeleteWorkCenter() throws Exception {
        workCenter("WC-L1", "ST-L1-01", "ST-L1-02");
        putJson("/workcenters/WC-L1", "{\"name\":\"新名称\",\"workshop\":\"W09\",\"outsourced\":true}", writer())
            .andExpect(status().isOk()).andExpect(jsonPath("$.data.name").value("新名称")).andExpect(jsonPath("$.data.outsourced").value(true));
        putJson("/workcenters/WC-L1/stations/ST-L1-01", "{\"name\":\"一号位\"}", writer())
            .andExpect(status().isOk()).andExpect(jsonPath("$.data.stations[0].name").value("一号位"));
        putJson("/workcenters/WC-NOPE/stations/ST-L1-01", "{\"name\":\"x\"}", writer()).andExpect(status().isNotFound())
            .andExpect(jsonPath("$.code").value("STATION_NOT_FOUND"));
        putJson("/workcenters/WC-L1", "{\"name\":\"\",\"workshop\":\"W09\",\"outsourced\":true}", writer()).andExpect(status().isBadRequest());
        putJson("/workcenters/WC-L1", "{\"name\":\"x\",\"workshop\":\"W09\",\"outsourced\":true}", viewer()).andExpect(status().isForbidden());

        // 删除工位：列表隐藏，单查的工作中心不再包含该工位
        del("/workcenters/WC-L1/stations/ST-L1-02", writer()).andExpect(status().isNoContent());
        mvc.perform(get("/workcenters/WC-L1").with(viewer())).andExpect(jsonPath("$.data.stations.length()").value(1));
        del("/workcenters/WC-L1/stations/ST-L1-02", writer()).andExpect(status().isNotFound());
        putJson("/workcenters/WC-L1/stations/ST-L1-02", "{\"name\":\"x\"}", writer()).andExpect(status().isNotFound());
        verify(workOrders).references(null, "ST-L1-02");

        // 删除工作中心（连同工位）
        del("/workcenters/WC-L1", writer()).andExpect(status().isNoContent());
        verify(workOrders).references("WC-L1", null);
        mvc.perform(get("/workcenters/WC-L1").with(viewer())).andExpect(status().isNotFound());
        mvc.perform(get("/workcenters").with(viewer())).andExpect(jsonPath("$.data[?(@.code=='WC-L1')]").isEmpty());
        mvc.perform(get("/workcenters").param("includeDeleted", "true").with(viewer()))
            .andExpect(jsonPath("$.data[?(@.code=='WC-L1')].deleted").value(true))
            .andExpect(jsonPath("$.data[?(@.code=='WC-L1')].stations[*].deleted").value(org.hamcrest.Matchers.everyItem(org.hamcrest.Matchers.is(true))));
        assertDeleted("station", "ST-L1-01");
        del("/workcenters/WC-L1", writer()).andExpect(status().isNotFound());
        putJson("/workcenters/WC-L1", "{\"name\":\"x\",\"workshop\":\"W09\",\"outsourced\":true}", writer()).andExpect(status().isNotFound());
        post("/workcenters/WC-L1/stations", "{\"code\":\"ST-L1-03\",\"name\":\"x\"}", admin()).andExpect(status().isNotFound());
        // 编码不可复用
        post("/workcenters", "{\"code\":\"WC-L1\",\"name\":\"x\",\"workshop\":\"W01\",\"outsourced\":false}", admin()).andExpect(status().isConflict());

        // 已删除的工作中心不能被新工艺路线发布引用
        material("L1-FG", "FG", "MAKE");
        post("/routings", routingBody("L1-FG", "R01", "2026-10-01", "WC-L1", "装配"), admin()).andExpect(status().isCreated());
        post("/routings/L1-FG/R01/release", "", admin()).andExpect(status().isUnprocessableEntity()).andExpect(jsonPath("$.code").value("WORK_CENTER_NOT_FOUND"));
    }

    private void assertDeleted(String table, String code) {
        org.assertj.core.api.Assertions.assertThat(jdbc.queryForObject("select deleted from " + table + " where code = ?", Boolean.class, code)).isTrue();
    }

    /** 引用类型 WORK_ORDER：工位 / 工作中心被执行中的工单引用 → 409 MASTERDATA_IN_USE，数据不变。 */
    @Test void stationInUseByWorkOrder() throws Exception {
        workCenter("WC-L2", "ST-L2-01");
        when(workOrders.references(null, "ST-L2-01")).thenReturn(ApiResponse.ok(new WorkOrderReferenceView(2, List.of("WO-1", "WO-2"))));
        del("/workcenters/WC-L2/stations/ST-L2-01", writer()).andExpect(status().isConflict())
            .andExpect(jsonPath("$.code").value("MASTERDATA_IN_USE"))
            .andExpect(jsonPath("$.data.type").value("masterdata-in-use"))
            .andExpect(jsonPath("$.data.referenceType").value("WORK_ORDER"))
            .andExpect(jsonPath("$.data.references[1]").value("WO-2"));
        when(workOrders.references("WC-L2", null)).thenReturn(ApiResponse.ok(new WorkOrderReferenceView(1, List.of("WO-3"))));
        del("/workcenters/WC-L2", writer()).andExpect(status().isConflict()).andExpect(jsonPath("$.data.referenceType").value("WORK_ORDER"));
        mvc.perform(get("/workcenters/WC-L2").with(viewer())).andExpect(status().isOk()).andExpect(jsonPath("$.data.stations[0].code").value("ST-L2-01"));
    }

    /** 工单服务不可用 / 空响应：503，不放行；工单服务拒绝令牌：403。 */
    @Test void workOrderServiceUnavailableRefusesDelete() throws Exception {
        workCenter("WC-L3", "ST-L3-01");
        when(workOrders.references(any(), any())).thenThrow(new IllegalStateException("connection refused"));
        del("/workcenters/WC-L3/stations/ST-L3-01", writer()).andExpect(status().isServiceUnavailable())
            .andExpect(jsonPath("$.code").value("WORKORDER_SERVICE_UNAVAILABLE"));
        del("/workcenters/WC-L3", writer()).andExpect(status().isServiceUnavailable());
        reset(workOrders);
        when(workOrders.references(any(), any())).thenReturn(null);
        del("/workcenters/WC-L3", writer()).andExpect(status().isServiceUnavailable());
        reset(workOrders);
        when(workOrders.references(any(), any())).thenThrow(mock(FeignException.Forbidden.class));
        del("/workcenters/WC-L3", writer()).andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("ACCESS_DENIED"));
        mvc.perform(get("/workcenters/WC-L3").with(viewer())).andExpect(status().isOk());
    }

    /** 引用类型 ROUTING：被未作废的工艺路线引用 → 409；工艺路线作废后可删除。 */
    @Test void workCenterInUseByRouting() throws Exception {
        workCenter("WC-L4");
        material("L4-FG", "FG", "MAKE");
        post("/routings", routingBody("L4-FG", "R01", "2026-10-01", "WC-L4", "装配"), admin()).andExpect(status().isCreated());
        del("/workcenters/WC-L4", writer()).andExpect(status().isConflict())
            .andExpect(jsonPath("$.data.type").value("masterdata-in-use"))
            .andExpect(jsonPath("$.data.referenceType").value("ROUTING"))
            .andExpect(jsonPath("$.data.references[0]").value("L4-FG/R01"));
        verifyNoInteractions(workOrders);
        post("/routings/L4-FG/R01/release", "", admin()).andExpect(status().isOk());
        del("/workcenters/WC-L4", writer()).andExpect(status().isConflict());
        post("/routings/L4-FG/R01/retire", "", admin()).andExpect(status().isOk()).andExpect(jsonPath("$.data.status").value("OBSOLETE"));
        del("/workcenters/WC-L4", writer()).andExpect(status().isNoContent());
    }

    /** 引用类型 BOM：物料被未作废的 BOM 引用时不能停用（作为组件或成品）；BOM 作废后可停用；停用后再启用不检查。 */
    @Test void materialInUseByBom() throws Exception {
        material("L5-FG", "FG", "MAKE");
        material("L5-RM", "RM", "BUY");
        material("L5-FREE", "RM", "BUY");
        post("/boms", bomBody("L5-FG", "A01", "2026-10-01", "L5-RM"), admin()).andExpect(status().isCreated());
        putJson("/materials/L5-RM", materialUpdate(false, 0), writer()).andExpect(status().isConflict())
            .andExpect(jsonPath("$.code").value("MASTERDATA_IN_USE"))
            .andExpect(jsonPath("$.data.type").value("masterdata-in-use"))
            .andExpect(jsonPath("$.data.referenceType").value("BOM"))
            .andExpect(jsonPath("$.data.references[0]").value("L5-FG/A01"));
        putJson("/materials/L5-FG", materialUpdate(false, 0), writer()).andExpect(status().isConflict())
            .andExpect(jsonPath("$.data.referenceType").value("BOM"));
        // 未被引用的物料可以停用，再启用
        putJson("/materials/L5-FREE", materialUpdate(false, 0), writer()).andExpect(status().isOk()).andExpect(jsonPath("$.data.active").value(false));
        putJson("/materials/L5-FREE", materialUpdate(true, 1), writer()).andExpect(status().isOk()).andExpect(jsonPath("$.data.active").value(true));
        // 草稿删除后不再引用
        del("/boms/L5-FG/A01", writer()).andExpect(status().isNoContent());
        putJson("/materials/L5-RM", materialUpdate(false, 0), writer()).andExpect(status().isOk());
        // 已停用的物料再次保存为停用：不重复检查
        putJson("/materials/L5-RM", materialUpdate(false, 1), writer()).andExpect(status().isOk());
    }

    // ------------------------------------------------------------------ BOM / 工艺路线版本

    /** BOM：草稿可删（明细一并删除）；已发布不可删、可作废；作废后不再生效，作废非已发布 409；权限：作废需 masterdata:release。 */
    @Test void bomRetireAndDeleteDraft() throws Exception {
        workCenter("WC-L6");
        material("L6-FG", "FG", "MAKE");
        material("L6-RM", "RM", "BUY");
        post("/routings", routingBody("L6-FG", "R01", "2026-09-01", "WC-L6", "装配"), admin()).andExpect(status().isCreated());
        post("/routings/L6-FG/R01/release", "", admin()).andExpect(status().isOk());
        post("/boms", bomBody("L6-FG", "A01", "2026-10-01", "L6-RM"), admin()).andExpect(status().isCreated());
        post("/boms/L6-FG/A01/retire", "", admin()).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("BOM_NOT_RELEASED"));
        post("/boms/L6-FG/A01/release", "", admin()).andExpect(status().isOk());
        del("/boms/L6-FG/A01", writer()).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("BOM_NOT_DRAFT"));
        mvc.perform(get("/boms/L6-FG/effective").param("date", "2026-10-15").with(viewer())).andExpect(status().isOk());
        post("/boms/L6-FG/A01/retire", "", writer()).andExpect(status().isForbidden());
        post("/boms/L6-FG/A01/retire", "", admin()).andExpect(status().isOk()).andExpect(jsonPath("$.data.status").value("OBSOLETE"));
        mvc.perform(get("/boms/L6-FG/effective").param("date", "2026-10-15").with(viewer())).andExpect(status().isNotFound());
        post("/boms/L6-FG/A01/retire", "", admin()).andExpect(status().isConflict());
        del("/boms/L6-FG/A01", writer()).andExpect(status().isConflict());
        post("/boms/L6-FG/A09/retire", "", admin()).andExpect(status().isNotFound());
        post("/boms/L6-NOPE/A01/retire", "", admin()).andExpect(status().isNotFound());
        // 作废后可发布新版本
        post("/boms", bomBody("L6-FG", "A02", "2026-11-01", "L6-RM"), admin()).andExpect(status().isCreated());
        post("/boms/L6-FG/A02/release", "", admin()).andExpect(status().isOk());
        // 草稿删除：明细一并删除，版本可再次创建
        post("/boms", bomBody("L6-FG", "A03", "2026-12-01", "L6-RM"), admin()).andExpect(status().isCreated());
        del("/boms/L6-FG/A03", viewer()).andExpect(status().isForbidden());
        del("/boms/L6-FG/A03", writer()).andExpect(status().isNoContent());
        org.assertj.core.api.Assertions.assertThat(jdbc.queryForObject("select count(*) from bom_line where product_code = 'L6-FG' and version = 'A03'", Integer.class)).isZero();
        mvc.perform(get("/boms/L6-FG/A03").with(viewer())).andExpect(status().isNotFound());
        del("/boms/L6-FG/A03", writer()).andExpect(status().isNotFound());
        post("/boms", bomBody("L6-FG", "A03", "2026-12-01", "L6-RM"), admin()).andExpect(status().isCreated());
        verify(workOrders).versionReferences("BOM", "L6-FG", "A03");
    }

    /** 草稿只有从未被任何工单引用时才能硬删除：曾被引用 409 masterdata-in-use（WORK_ORDER），工单服务不可用 503，草稿保留。 */
    @Test void draftReferencedByWorkOrderCannotBeDeleted() throws Exception {
        workCenter("WC-L8");
        material("L8-FG", "FG", "MAKE");
        material("L8-RM", "RM", "BUY");
        post("/routings", routingBody("L8-FG", "R01", "2026-10-01", "WC-L8", "装配"), admin()).andExpect(status().isCreated());
        post("/boms", bomBody("L8-FG", "A01", "2026-10-01", "L8-RM"), admin()).andExpect(status().isCreated());
        when(workOrders.versionReferences("BOM", "L8-FG", "A01")).thenReturn(ApiResponse.ok(new WorkOrderReferenceView(1, List.of("WO-9"))));
        when(workOrders.versionReferences("ROUTING", "L8-FG", "R01")).thenReturn(ApiResponse.ok(new WorkOrderReferenceView(2, List.of("WO-9", "WO-10"))));
        del("/boms/L8-FG/A01", writer()).andExpect(status().isConflict())
            .andExpect(jsonPath("$.code").value("MASTERDATA_IN_USE"))
            .andExpect(jsonPath("$.data.type").value("masterdata-in-use"))
            .andExpect(jsonPath("$.data.referenceType").value("WORK_ORDER"))
            .andExpect(jsonPath("$.data.references[0]").value("WO-9"));
        del("/routings/L8-FG/R01", writer()).andExpect(status().isConflict())
            .andExpect(jsonPath("$.data.type").value("masterdata-in-use"))
            .andExpect(jsonPath("$.data.references[1]").value("WO-10"));
        when(workOrders.versionReferences("BOM", "L8-FG", "A01")).thenThrow(new IllegalStateException("connection refused"));
        del("/boms/L8-FG/A01", writer()).andExpect(status().isServiceUnavailable()).andExpect(jsonPath("$.code").value("WORKORDER_SERVICE_UNAVAILABLE"));
        mvc.perform(get("/boms/L8-FG/A01").with(viewer())).andExpect(status().isOk());
        mvc.perform(get("/routings/L8-FG/R01").with(viewer())).andExpect(status().isOk());
        org.assertj.core.api.Assertions.assertThat(jdbc.queryForObject("select count(*) from bom_line where product_code = 'L8-FG' and version = 'A01'", Integer.class)).isEqualTo(1);
    }

    /** 作废唯一生效版本后不恢复上一版本：effective 查询 404，工单下达因此 422（新发布版本后恢复）。 */
    @Test void retiringOnlyEffectiveVersionLeavesNoEffectiveVersion() throws Exception {
        workCenter("WC-L9");
        material("L9-FG", "FG", "MAKE");
        material("L9-RM", "RM", "BUY");
        post("/routings", routingBody("L9-FG", "R01", "2026-09-01", "WC-L9", "装配"), admin()).andExpect(status().isCreated());
        post("/routings/L9-FG/R01/release", "", admin()).andExpect(status().isOk());
        post("/routings", routingBody("L9-FG", "R02", "2026-10-01", "WC-L9", "装配"), admin()).andExpect(status().isCreated());
        post("/routings/L9-FG/R02/release", "", admin()).andExpect(status().isOk());
        post("/routings/L9-FG/R02/retire", "", admin()).andExpect(status().isOk());
        // R01 已在 R02 发布时被截断，作废 R02 不恢复 R01 的生效区间
        mvc.perform(get("/routings/L9-FG/effective").param("date", "2026-10-15").with(viewer())).andExpect(status().isNotFound());
        post("/routings", routingBody("L9-FG", "R03", "2026-10-02", "WC-L9", "装配"), admin()).andExpect(status().isCreated());
        post("/routings/L9-FG/R03/release", "", admin()).andExpect(status().isOk());
        mvc.perform(get("/routings/L9-FG/effective").param("date", "2026-10-15").with(viewer()))
            .andExpect(status().isOk()).andExpect(jsonPath("$.data.version").value("R03"));
    }

    /** 工艺路线：工序只在草稿中可改（已发布 / 已作废 409 ROUTING_NOT_DRAFT）；作废、删除草稿。 */
    @Test void routingOperationsEditableOnlyInDraft() throws Exception {
        workCenter("WC-L7");
        material("L7-FG", "FG", "MAKE");
        post("/routings", routingBody("L7-FG", "R01", "2026-10-01", "WC-L7", "装配"), admin()).andExpect(status().isCreated());
        putJson("/routings/L7-FG/R01", routingBody("L7-FG", "R01", "2026-10-01", "WC-L7", "总装"), writer())
            .andExpect(status().isOk()).andExpect(jsonPath("$.data.operations[0].name").value("总装"));
        post("/routings/L7-FG/R01/release", "", admin()).andExpect(status().isOk());
        putJson("/routings/L7-FG/R01", routingBody("L7-FG", "R01", "2026-10-01", "WC-L7", "改名"), writer())
            .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("ROUTING_NOT_DRAFT"));
        del("/routings/L7-FG/R01", writer()).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("ROUTING_NOT_DRAFT"));
        post("/routings/L7-FG/R01/retire", "", admin()).andExpect(status().isOk()).andExpect(jsonPath("$.data.status").value("OBSOLETE"));
        putJson("/routings/L7-FG/R01", routingBody("L7-FG", "R01", "2026-10-01", "WC-L7", "改名"), writer())
            .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("ROUTING_NOT_DRAFT"));
        post("/routings/L7-FG/R01/retire", "", admin()).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("ROUTING_NOT_RELEASED"));
        mvc.perform(get("/routings/L7-FG/R01").with(viewer())).andExpect(jsonPath("$.data.operations[0].name").value("总装"));

        post("/routings", routingBody("L7-FG", "R02", "2026-11-01", "WC-L7", "装配"), admin()).andExpect(status().isCreated());
        del("/routings/L7-FG/R02", writer()).andExpect(status().isNoContent());
        org.assertj.core.api.Assertions.assertThat(jdbc.queryForObject("select count(*) from routing_operation where product_code = 'L7-FG' and version = 'R02'", Integer.class)).isZero();
        del("/routings/L7-FG/R02", writer()).andExpect(status().isNotFound());
        post("/routings/L7-NOPE/R01/retire", "", admin()).andExpect(status().isNotFound());
    }
}
