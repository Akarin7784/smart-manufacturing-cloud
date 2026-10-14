package com.smartmanufacturing.masterdata;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultMatcher;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** #71：主数据所有错误类别（not-found / 冲突 / 业务规则 / validation-error / 403）都返回 application/problem+json，字段与工单服务一致。 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class MasterdataProblemIntegrationTest {
    @Autowired MockMvc mvc;
    @MockitoBean org.springframework.security.oauth2.jwt.JwtDecoder decoder;

    private static RequestPostProcessor admin() {
        return jwt().authorities(new SimpleGrantedAuthority("masterdata:read"), new SimpleGrantedAuthority("masterdata:write"),
            new SimpleGrantedAuthority("masterdata:release"));
    }
    private static ResultMatcher problem(int status, String type, String code, String instance) {
        return ResultMatcher.matchAll(status().is(status),
            content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON),
            jsonPath("$.type").value(type), jsonPath("$.title").isNotEmpty(), jsonPath("$.status").value(status),
            jsonPath("$.detail").isNotEmpty(), jsonPath("$.instance").value(instance), jsonPath("$.code").value(code),
            jsonPath("$.message").doesNotExist());
    }

    @Test void notFoundIsProblem() throws Exception {
        mvc.perform(get("/materials/PX-NOPE").with(admin())).andExpect(problem(404, "material-not-found", "MATERIAL_NOT_FOUND", "/materials/PX-NOPE"));
        mvc.perform(get("/workcenters/PX-NOPE").with(admin())).andExpect(problem(404, "workcenter-not-found", "WORK_CENTER_NOT_FOUND", "/workcenters/PX-NOPE"));
    }

    @Test void conflictIsProblem() throws Exception {
        String body = "{\"code\":\"PX-MAT\",\"name\":\"x\",\"type\":\"PART\",\"uom\":\"件\",\"traceMode\":\"LOT\",\"source\":\"MAKE\"}";
        mvc.perform(post("/materials").with(admin()).contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isCreated());
        mvc.perform(post("/materials").with(admin()).contentType(MediaType.APPLICATION_JSON).content(body))
            .andExpect(problem(409, "material-exists", "MATERIAL_EXISTS", "/materials"));
    }

    @Test void validationErrorsAreProblem() throws Exception {
        mvc.perform(post("/materials").with(admin()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"code\":\"bad code\",\"name\":\"x\",\"type\":\"PART\",\"uom\":\"件\",\"traceMode\":\"LOT\",\"source\":\"MAKE\"}"))
            .andExpect(problem(400, "validation-error", "INVALID_ARGUMENT", "/materials")).andExpect(jsonPath("$.detail").value(org.hamcrest.Matchers.startsWith("code: ")));
        mvc.perform(post("/materials").with(admin()).contentType(MediaType.APPLICATION_JSON).content("{not json"))
            .andExpect(problem(400, "validation-error", "INVALID_ARGUMENT", "/materials"));
        mvc.perform(get("/boms/PX-FG/effective").param("date", "not-a-date").with(admin()))
            .andExpect(problem(400, "validation-error", "INVALID_ARGUMENT", "/boms/PX-FG/effective"));
        mvc.perform(get("/boms/PX-FG/explosion").param("quantity", "-1").with(admin()))
            .andExpect(problem(400, "validation-error", "INVALID_ARGUMENT", "/boms/PX-FG/explosion"));
    }

    @Test void methodLevelForbiddenStaysSecurityProblem() throws Exception {
        mvc.perform(post("/materials").with(jwt().authorities(new SimpleGrantedAuthority("masterdata:read"))).contentType(MediaType.APPLICATION_JSON)
                .content("{\"code\":\"PX-RO\",\"name\":\"x\",\"type\":\"PART\",\"uom\":\"件\",\"traceMode\":\"LOT\",\"source\":\"MAKE\"}"))
            .andExpect(status().isForbidden()).andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
            .andExpect(jsonPath("$.type").value("access-denied"));
    }

    @Test void unknownRouteKeepsStatusAsProblem() throws Exception {
        mvc.perform(get("/no-such-endpoint").with(admin())).andExpect(status().isNotFound())
            .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON));
    }
}
