package com.smartmanufacturing.quality;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import java.util.List;
import java.util.Map;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * 质检服务 自身 401/403 的 problem+json 格式测试。
 * <p>
 * 按 docs/authentication.md 中的服务 Problem 表断言：Content-Type 为 application/problem+json，
 * 字段恰好为 type/title/status/detail/instance（不含旧的 code），type 与固定中文 title 一一对应，
 * instance 为请求路径且不含查询串，401 附带 WWW-Authenticate: Bearer。
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class SecurityProblemTest {
    /** Problem 表：type → 固定 title（与 cloud-common 的 SecurityProblem 一致）。 */
    private static final Map<String, String> TITLES = Map.of(
        "authentication-required", "需要认证",
        "access-denied", "没有访问权限");
    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Autowired MockMvc mvc;

    /** 枚举与 Problem 表一致：状态码、type、title 均固定。 */
    @Test void enumMatchesProblemTable() {
        assertThat(SecurityProblemType.AUTHENTICATION_REQUIRED.status().value()).isEqualTo(401);
        assertThat(SecurityProblemType.ACCESS_DENIED.status().value()).isEqualTo(403);
        for (SecurityProblemType t : SecurityProblemType.values()) assertThat(TITLES).containsEntry(t.value(), t.title());
    }

    /** 拿不到请求时整个省略 instance，不输出 null。 */
    @Test void instanceOmittedWithoutRequest() {
        Map<String, Object> body = QualityProblemHandler.body("access-denied", "没有访问权限", 403, "没有访问权限", null);
        assertThat(body).containsOnlyKeys("type", "title", "status", "detail");
    }

    /** 无令牌、令牌无法解析为 401；只读令牌写入（方法级拒绝）与无权限令牌读取为 403。 */
    @Test void qualityChain() throws Exception {
        assertProblem(get("/quality/ncrs").param("token", "secret"), 401, "authentication-required", "/quality/ncrs");
        assertProblem(get("/quality/ncrs").header("Authorization", "Bearer not-a-jwt"), 401, "authentication-required", "/quality/ncrs");
        assertProblem(get("/quality/ncrs").with(jwt()), 403, "access-denied", "/quality/ncrs");
        assertProblem(post("/quality/ncrs/NCR-SEC/close").header("Idempotency-Key", "k-sec")
            .with(jwt().authorities(new SimpleGrantedAuthority("quality:read"))), 403, "access-denied", "/quality/ncrs/NCR-SEC/close");
    }

    /** 执行请求并按 Problem 表断言五个字段；path 为期望的 instance（请求带查询串时也不应出现）。 */
    private void assertProblem(MockHttpServletRequestBuilder request, int status, String type, String path) throws Exception {
        MvcResult r = mvc.perform(request).andReturn();
        assertThat(r.getResponse().getStatus()).as(r.getResponse().getContentAsString()).isEqualTo(status);
        assertThat(MediaType.parseMediaType(r.getResponse().getContentType()).isCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON)).isTrue();
        JsonNode body = MAPPER.readTree(r.getResponse().getContentAsByteArray());
        List<String> fields = new java.util.ArrayList<>();
        body.fieldNames().forEachRemaining(fields::add);
        assertThat(fields).containsExactlyInAnyOrder("type", "title", "status", "detail", "instance");
        assertThat(body.get("type").asText()).isEqualTo(type);
        assertThat(body.get("title").asText()).isEqualTo(TITLES.get(type));
        assertThat(body.get("status").asInt()).isEqualTo(status);
        assertThat(body.get("detail").asText()).isNotBlank();
        assertThat(body.get("instance").asText()).isEqualTo(path);
        if (status == 401) assertThat(r.getResponse().getHeader("WWW-Authenticate")).isEqualTo("Bearer");
        else assertThat(r.getResponse().getHeader("WWW-Authenticate")).isNull();
    }
}
