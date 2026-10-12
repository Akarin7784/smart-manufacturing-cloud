package com.smartmanufacturing.masterdata;
import org.junit.jupiter.api.*;
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
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * 主数据服务接口级测试（H2，无外部依赖，不加载演示数据）：保护物料乐观锁、BOM/工艺版本生命周期、
 * 跨聚合发布校验与方法级权限。每个用例使用独立编码，避免共享内存库造成用例间干扰。
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class MasterdataIntegrationTest {
    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @MockitoBean org.springframework.security.oauth2.jwt.JwtDecoder decoder;

    /** 工艺员/管理员：读、写、发布全部权限。 */
    private static RequestPostProcessor admin() {
        return jwt().authorities(new SimpleGrantedAuthority("masterdata:read"), new SimpleGrantedAuthority("masterdata:write"),
            new SimpleGrantedAuthority("masterdata:release"));
    }
    private static RequestPostProcessor viewer() { return jwt().authorities(new SimpleGrantedAuthority("masterdata:read")); }
    private ResultActions post(String url, String body, RequestPostProcessor who) throws Exception {
        return mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post(url).with(who)
            .contentType(MediaType.APPLICATION_JSON).content(body));
    }
    private void material(String code, String type, String source) throws Exception {
        post("/materials", "{\"code\":\"" + code + "\",\"name\":\"" + code + "\",\"type\":\"" + type + "\",\"uom\":\"件\",\"traceMode\":\"LOT\",\"source\":\"" + source + "\"}", admin())
            .andExpect(status().isCreated());
    }
    private static String bomBody(String product, String version, String from, String component, String opNo) {
        return "{\"productCode\":\"" + product + "\",\"version\":\"" + version + "\",\"effectiveFrom\":\"" + from + "\",\"lines\":[{\"lineNo\":10,\"componentCode\":\""
            + component + "\",\"qtyPer\":2,\"uom\":\"件\",\"scrapRate\":0,\"issueMode\":\"PICK\"" + (opNo == null ? "" : ",\"consumeOpNo\":\"" + opNo + "\"") + ",\"phantom\":false}]}";
    }
    private static String routingBody(String product, String version, String from, String wc) {
        return "{\"productCode\":\"" + product + "\",\"version\":\"" + version + "\",\"effectiveFrom\":\"" + from + "\",\"operations\":["
            + "{\"opNo\":\"OP20\",\"name\":\"装配\",\"workCenterCode\":\"" + wc + "\",\"setupMinutes\":5,\"runSecondsPerUnit\":60,\"reportPoint\":true,\"firstArticleRequired\":false,\"inspectionPointCodes\":[\"IP-ASM-TQ\"]},"
            + "{\"opNo\":\"OP10\",\"name\":\"上线\",\"workCenterCode\":\"" + wc + "\",\"setupMinutes\":0,\"runSecondsPerUnit\":30,\"reportPoint\":false,\"firstArticleRequired\":false,\"inspectionPointCodes\":[]}]}";
    }

    /** 物料：创建、重复编码 409、按版本更新、旧版本更新 409（防并发覆盖）。 */
    @Test void materialOptimisticLocking() throws Exception {
        material("T1-MAT", "PART", "MAKE");
        post("/materials", "{\"code\":\"T1-MAT\",\"name\":\"x\",\"type\":\"PART\",\"uom\":\"件\",\"traceMode\":\"LOT\",\"source\":\"MAKE\"}", admin())
            .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("MATERIAL_EXISTS"));
        String update = "{\"name\":\"新名称\",\"uom\":\"件\",\"traceMode\":\"SERIAL\",\"active\":true,\"expectedVersion\":0}";
        mvc.perform(put("/materials/T1-MAT").with(admin()).contentType(MediaType.APPLICATION_JSON).content(update))
            .andExpect(status().isOk()).andExpect(jsonPath("$.data.version").value(1)).andExpect(jsonPath("$.data.traceMode").value("SERIAL"));
        mvc.perform(put("/materials/T1-MAT").with(admin()).contentType(MediaType.APPLICATION_JSON).content(update))
            .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("MATERIAL_VERSION_CONFLICT"));
        mvc.perform(put("/materials/NOPE").with(admin()).contentType(MediaType.APPLICATION_JSON).content(update))
            .andExpect(status().isNotFound());
    }

    /** 编码格式在入口校验（小写、空格被拒绝），避免同一物料出现多种写法。 */
    @Test void rejectsMalformedCode() throws Exception {
        post("/materials", "{\"code\":\"bad code\",\"name\":\"x\",\"type\":\"PART\",\"uom\":\"件\",\"traceMode\":\"LOT\",\"source\":\"MAKE\"}", admin())
            .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_ARGUMENT"));
    }

    /** 只读身份可以查询但不能写入，拒绝发生在数据库写入之前。 */
    @Test void viewerCannotWrite() throws Exception {
        mvc.perform(get("/materials").with(viewer())).andExpect(status().isOk());
        post("/materials", "{\"code\":\"T2-MAT\",\"name\":\"x\",\"type\":\"PART\",\"uom\":\"件\",\"traceMode\":\"LOT\",\"source\":\"MAKE\"}", viewer())
            .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("ACCESS_DENIED"));
        org.junit.jupiter.api.Assertions.assertEquals(0, jdbc.queryForObject("select count(*) from material where code = 'T2-MAT'", Integer.class));
    }

    /** 只有写权限、没有发布权限时不能发布 BOM（权限分离）。 */
    @Test void writerWithoutReleasePermissionCannotRelease() throws Exception {
        RequestPostProcessor writer = jwt().authorities(new SimpleGrantedAuthority("masterdata:read"), new SimpleGrantedAuthority("masterdata:write"));
        post("/boms/ANY/A01/release", "", writer).andExpect(status().isForbidden());
    }

    /** 工作中心与工位：创建、工位挂接、未知工作中心 404、重复工位 409。 */
    @Test void workCenterAndStations() throws Exception {
        post("/workcenters", "{\"code\":\"WC-T3\",\"name\":\"测试中心\",\"workshop\":\"W09\",\"outsourced\":false}", admin()).andExpect(status().isCreated());
        post("/workcenters/WC-T3/stations", "{\"code\":\"ST-T3-01\",\"name\":\"工位1\"}", admin())
            .andExpect(status().isCreated()).andExpect(jsonPath("$.data.stations[0].code").value("ST-T3-01"));
        post("/workcenters/WC-T3/stations", "{\"code\":\"ST-T3-01\",\"name\":\"工位1\"}", admin()).andExpect(status().isConflict());
        post("/workcenters/WC-NOPE/stations", "{\"code\":\"ST-T3-02\",\"name\":\"x\"}", admin()).andExpect(status().isNotFound());
    }

    /**
     * BOM 与工艺完整生命周期：
     * 1) 消耗工序引用缺少生效工艺 → 422；2) 发布工艺后 BOM 可发布；3) 已发布版本不可修改/重复发布 → 409；
     * 4) 新版本生效日不晚于旧版本 → 409；5) 发布新版本后旧版本被截断，按日期查询分别命中两个版本。
     */
    @Test void versionedLifecycle() throws Exception {
        material("T4-FG", "FG", "MAKE");
        material("T4-RM", "RM", "BUY");
        post("/workcenters", "{\"code\":\"WC-T4\",\"name\":\"装配\",\"workshop\":\"W02\",\"outsourced\":false}", admin()).andExpect(status().isCreated());

        post("/boms", bomBody("T4-FG", "A01", "2026-10-01", "T4-RM", "OP20"), admin()).andExpect(status().isCreated())
            .andExpect(jsonPath("$.data.status").value("DRAFT"));
        post("/boms/T4-FG/A01/release", "", admin()).andExpect(status().isUnprocessableEntity()).andExpect(jsonPath("$.code").value("BOM_ROUTING_MISSING"));

        post("/routings", routingBody("T4-FG", "R01", "2026-09-01", "WC-T4"), admin()).andExpect(status().isCreated())
            // 工序按工序号排序，与提交顺序无关。
            .andExpect(jsonPath("$.data.operations[0].opNo").value("OP10"));
        post("/routings/T4-FG/R01/release", "", admin()).andExpect(status().isOk()).andExpect(jsonPath("$.data.status").value("RELEASED"));
        post("/boms/T4-FG/A01/release", "", admin()).andExpect(status().isOk()).andExpect(jsonPath("$.data.status").value("RELEASED"));

        post("/boms/T4-FG/A01/release", "", admin()).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("BOM_NOT_DRAFT"));
        mvc.perform(put("/boms/T4-FG/A01").with(admin()).contentType(MediaType.APPLICATION_JSON).content(bomBody("T4-FG", "A01", "2026-10-01", "T4-RM", null)))
            .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("BOM_NOT_DRAFT"));

        post("/boms", bomBody("T4-FG", "A02", "2026-10-01", "T4-RM", null), admin()).andExpect(status().isCreated());
        post("/boms/T4-FG/A02/release", "", admin()).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("EFFECTIVE_DATE_CONFLICT"));
        mvc.perform(put("/boms/T4-FG/A02").with(admin()).contentType(MediaType.APPLICATION_JSON).content(bomBody("T4-FG", "A02", "2026-11-01", "T4-RM", null)))
            .andExpect(status().isOk());
        post("/boms/T4-FG/A02/release", "", admin()).andExpect(status().isOk());

        mvc.perform(get("/boms/T4-FG/A01").with(viewer())).andExpect(jsonPath("$.data.effectiveTo").value("2026-11-01"));
        mvc.perform(get("/boms/T4-FG/effective").param("date", "2026-10-31").with(viewer())).andExpect(jsonPath("$.data.version").value("A01"));
        mvc.perform(get("/boms/T4-FG/effective").param("date", "2026-11-01").with(viewer())).andExpect(jsonPath("$.data.version").value("A02"));
        mvc.perform(get("/boms/T4-FG/effective").param("date", "2026-01-01").with(viewer()))
            .andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("BOM_NOT_EFFECTIVE"));
    }

    /** 发布时检测跨层循环：A 的已发布 BOM 含 B，再发布含 A 的 B 草稿必须 422。 */
    @Test void releaseRejectsIndirectCycle() throws Exception {
        material("T5-A", "SA", "MAKE");
        material("T5-B", "PART", "MAKE");
        post("/boms", bomBody("T5-A", "A01", "2026-10-01", "T5-B", null), admin()).andExpect(status().isCreated());
        post("/boms/T5-A/A01/release", "", admin()).andExpect(status().isOk());
        post("/boms", bomBody("T5-B", "A01", "2026-10-01", "T5-A", null), admin()).andExpect(status().isCreated());
        post("/boms/T5-B/A01/release", "", admin()).andExpect(status().isUnprocessableEntity()).andExpect(jsonPath("$.code").value("BOM_CYCLE"));
    }

    /** 草稿阶段规则：采购件不能建 BOM；不存在的组件 422；直接自引用 422；消耗工序不在路线中 422。 */
    @Test void draftAndReleaseRules() throws Exception {
        material("T6-FG", "FG", "MAKE");
        material("T6-BUY", "PURCHASED", "BUY");
        post("/boms", bomBody("T6-BUY", "A01", "2026-10-01", "T6-FG", null), admin())
            .andExpect(status().isUnprocessableEntity()).andExpect(jsonPath("$.code").value("BOM_PRODUCT_NOT_MAKE"));
        post("/boms", bomBody("T6-FG", "A01", "2026-10-01", "T6-MISSING", null), admin())
            .andExpect(status().isUnprocessableEntity()).andExpect(jsonPath("$.code").value("BOM_COMPONENT_NOT_FOUND"));
        post("/boms", bomBody("T6-FG", "A01", "2026-10-01", "T6-FG", null), admin())
            .andExpect(status().isUnprocessableEntity()).andExpect(jsonPath("$.code").value("BOM_CYCLE"));
        post("/workcenters", "{\"code\":\"WC-T6\",\"name\":\"x\",\"workshop\":\"W02\",\"outsourced\":false}", admin()).andExpect(status().isCreated());
        post("/routings", routingBody("T6-FG", "R01", "2026-09-01", "WC-T6"), admin()).andExpect(status().isCreated());
        post("/routings/T6-FG/R01/release", "", admin()).andExpect(status().isOk());
        post("/boms", bomBody("T6-FG", "A01", "2026-10-01", "T6-BUY", "OP99"), admin()).andExpect(status().isCreated());
        post("/boms/T6-FG/A01/release", "", admin()).andExpect(status().isUnprocessableEntity()).andExpect(jsonPath("$.code").value("BOM_OPERATION_NOT_FOUND"));
        // 草稿行引用不存在的工作中心在写入时即被拒绝。
        post("/routings", routingBody("T6-FG", "R02", "2026-12-01", "WC-NOPE"), admin())
            .andExpect(status().isUnprocessableEntity()).andExpect(jsonPath("$.code").value("WORK_CENTER_NOT_FOUND"));
    }

    /**
     * 数据库级兜底：每个产品至多一个“已发布且未截断”的版本（uk_bom_header_current）。
     * 1) 直接写库插入第二个开放版本被唯一约束拒绝；2) 绕过服务造成脏数据后，服务发布返回 409 BOM_RELEASE_CONFLICT 且整笔回滚。
     */
    @Test void uniqueCurrentVersionGuard() throws Exception {
        material("T7-FG", "PART", "MAKE");
        material("T7-RM", "RM", "BUY");
        post("/boms", bomBody("T7-FG", "A01", "2026-10-01", "T7-RM", null), admin()).andExpect(status().isCreated());
        post("/boms/T7-FG/A01/release", "", admin()).andExpect(status().isOk());
        org.junit.jupiter.api.Assertions.assertThrows(org.springframework.dao.DuplicateKeyException.class, () ->
            jdbc.update("insert into bom_header (product_code, version, status, effective_from) values ('T7-FG', 'A09', 'RELEASED', DATE '2027-01-01')"));
        // 构造脏数据：较早的 A00 保持开放、最新的 A01 已截断，服务只截断最新版本，发布 A02 时由约束兜底。
        jdbc.update("insert into bom_header (product_code, version, status, effective_from, effective_to) values ('T7-FG', 'A00', 'RELEASED', DATE '2026-09-01', DATE '2026-09-15')");
        jdbc.update("update bom_header set effective_to = DATE '2026-10-15' where product_code = 'T7-FG' and version = 'A01'");
        jdbc.update("update bom_header set effective_to = null where product_code = 'T7-FG' and version = 'A00'");
        post("/boms", bomBody("T7-FG", "A02", "2026-11-01", "T7-RM", null), admin()).andExpect(status().isCreated());
        post("/boms/T7-FG/A02/release", "", admin()).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("BOM_RELEASE_CONFLICT"));
        org.junit.jupiter.api.Assertions.assertEquals("DRAFT", jdbc.queryForObject("select status from bom_header where product_code = 'T7-FG' and version = 'A02'", String.class));
    }

    /** 未认证请求返回 401，不能用伪造角色头访问。 */
    @Test void anonymousRejected() throws Exception {
        mvc.perform(get("/materials").header("X-Role", "ADMIN")).andExpect(status().isUnauthorized());
    }
}
