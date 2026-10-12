package com.smartmanufacturing.auth;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.*;
import java.util.List;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.*;
import org.springframework.test.web.servlet.MockMvc;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * 质检角色与演示账号：经 V2 迁移加宽 role 列后可保存 QUALITY_INSPECTOR；
 * 通过真实登录取得令牌，断言 qc01 带 quality:read + quality:write，op01（操作员）只有 quality:read。
 * 使用独立内存库，避免与其他测试类共享账号数据。
 */
@SpringBootTest(properties = {
    "spring.datasource.url=jdbc:h2:mem:auth-demo-users;MODE=MySQL;DB_CLOSE_DELAY=-1",
    "auth.demo-users.qc01-password=test-qc01-password-2026",
    "auth.demo-users.op01-password=test-op01-password-2026"})
@AutoConfigureMockMvc
@ActiveProfiles("test")
class DemoUserQualityRoleTest {
    static final Path directory;
    static {
        try { directory = Files.createTempDirectory("manufacturing-auth-demo-"); }
        catch (Exception e) { throw new ExceptionInInitializerError(e); }
    }
    @DynamicPropertySource static void keyPath(DynamicPropertyRegistry registry) {
        registry.add("auth.private-key-path", () -> directory.resolve("signing-key.json").toString());
    }
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper mapper;
    @Autowired JwtDecoder decoder;
    @Autowired JdbcTemplate jdbc;

    private List<String> permissionsOf(String username, String password) throws Exception {
        String body = mvc.perform(post("/auth/login").contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsString(new LoginRequest(username, password))))
            .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        return decoder.decode(mapper.readTree(body).path("data").path("accessToken").asText()).getClaimAsStringList("permissions");
    }

    /** 质检员可读写质检，但不能写工单或出入库。 */
    @Test void qc01TokenCarriesQualityReadAndWrite() throws Exception {
        List<String> p = permissionsOf("qc01", "test-qc01-password-2026");
        assertTrue(p.containsAll(List.of("quality:read", "quality:write")));
        assertFalse(p.contains("workorder:write"));
        assertFalse(p.contains("inventory:write"));
        assertEquals("QUALITY_INSPECTOR", jdbc.queryForObject("select role from auth_user where username = 'qc01'", String.class));
    }

    /** 操作员（现有业务角色）只读质检：网关据此拒绝其处置请求。 */
    @Test void operatorTokenCarriesOnlyQualityRead() throws Exception {
        List<String> p = permissionsOf("op01", "test-op01-password-2026");
        assertTrue(p.contains("quality:read"));
        assertFalse(p.contains("quality:write"));
    }

    /** 管理员拥有质检读写。 */
    @Test void adminTokenCarriesQualityReadAndWrite() throws Exception {
        assertTrue(permissionsOf("admin", "test-admin-password-2026").containsAll(List.of("quality:read", "quality:write")));
    }

    /** 角色映射本身：只有 ADMIN 与 QUALITY_INSPECTOR 可写质检，其余角色至少可读。 */
    @Test void onlyAdminAndInspectorMayWriteQuality() {
        for (Role role : Role.values()) {
            assertTrue(role.permissions().contains("quality:read"), role + " 应可读质检");
            assertEquals(role == Role.ADMIN || role == Role.QUALITY_INSPECTOR, role.permissions().contains("quality:write"), role.name());
        }
    }

    /** 重启不覆盖已有演示账号：同名账号存在时跳过，不重置密码。 */
    @Test void seedingIsIdempotentPerUsername() {
        assertEquals(1, jdbc.queryForObject("select count(*) from auth_user where username = 'qc01'", Integer.class));
    }
}
