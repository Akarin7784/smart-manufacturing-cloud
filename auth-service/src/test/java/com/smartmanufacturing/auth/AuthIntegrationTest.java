package com.smartmanufacturing.auth;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nimbusds.jose.jwk.*;
import java.nio.file.*;
import java.util.List;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.*;
import org.springframework.test.web.servlet.MockMvc;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** 使用真实密码散列和 RSA 令牌，验证登录、锁定、公钥发布与管理员用户管理。 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class AuthIntegrationTest {
    static final Path directory;
    static {
        try { directory = Files.createTempDirectory("manufacturing-auth-test-"); }
        catch (Exception e) { throw new ExceptionInInitializerError(e); }
    }
    @DynamicPropertySource static void keyPath(DynamicPropertyRegistry registry) {
        registry.add("auth.private-key-path", () -> directory.resolve("signing-key.json").toString());
    }
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper mapper;
    @Autowired JdbcTemplate jdbc;
    @Autowired JwtDecoder decoder;
    @Autowired PasswordEncoder passwords;
    @Autowired AuthUserService users;
    static final String ADMIN_PASSWORD = "test-admin-password-2026";
    static final String USER_PASSWORD = "test-user-password-2026";

    /** 每个测试保留引导管理员，清理其他账号以及账号锁定状态。 */
    @BeforeEach void reset() {
        jdbc.update("delete from auth_user where username <> 'admin'");
        jdbc.update("update auth_user set failed_attempts = 0, locked_until = null, enabled = true, role = 'ADMIN' where username = 'admin'");
    }

    /** 通过真实登录接口取得令牌，不绕过密码校验或注入模拟身份。 */
    private String login(String username, String password) throws Exception {
        String response = mvc.perform(post("/auth/login").contentType(MediaType.APPLICATION_JSON)
            .content(mapper.writeValueAsString(new LoginRequest(username, password))))
            .andExpect(status().isOk()).andExpect(header().string("Cache-Control", "no-store"))
            .andReturn().getResponse().getContentAsString();
        return mapper.readTree(response).path("data").path("accessToken").asText();
    }

    /** 密码只以 BCrypt 散列保存；令牌包含固定受众和服务端角色。 */
    @Test void loginIssuesValidRsaTokenAndStoresHash() throws Exception {
        String access = login("admin", ADMIN_PASSWORD);
        var jwt = decoder.decode(access);
        assertEquals("http://localhost:8080", jwt.getIssuer().toString());
        assertEquals(List.of("manufacturing-api"), jwt.getAudience());
        assertEquals(List.of("ADMIN"), jwt.getClaimAsStringList("roles"));
        assertTrue(jwt.getClaimAsStringList("permissions").contains("user:manage"));
        String hash = jdbc.queryForObject("select password_hash from auth_user where username = 'admin'", String.class);
        assertNotEquals(ADMIN_PASSWORD, hash);
        assertTrue(passwords.matches(ADMIN_PASSWORD, hash));
        mvc.perform(get("/auth/me").header("Authorization", "Bearer " + access))
            .andExpect(status().isOk()).andExpect(jsonPath("$.data.username").value("admin"));
    }

    /** 未知账号和错误密码都返回同一业务码，避免泄露用户存在性。 */
    @Test void invalidCredentialsHaveUniformResponse() throws Exception {
        for (String name : List.of("missing", "admin")) {
            mvc.perform(post("/auth/login").contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsString(new LoginRequest(name, "wrong-password"))))
                .andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value("INVALID_CREDENTIALS"));
        }
    }

    /** 验证失败计数在错误响应后仍提交，第五次失败触发锁定，到期后允许正确密码。 */
    @Test void repeatedFailuresLockAccountAndExpire() throws Exception {
        for (int i = 0; i < 5; i++) {
            mvc.perform(post("/auth/login").contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsString(new LoginRequest("admin", "wrong-password"))))
                .andExpect(status().isUnauthorized());
        }
        assertEquals(5, jdbc.queryForObject("select failed_attempts from auth_user where username = 'admin'", Integer.class));
        mvc.perform(post("/auth/login").contentType(MediaType.APPLICATION_JSON)
            .content(mapper.writeValueAsString(new LoginRequest("admin", ADMIN_PASSWORD))))
            .andExpect(status().isUnauthorized());
        jdbc.update("update auth_user set locked_until = ? where username = 'admin'",
            java.sql.Timestamp.from(java.time.Instant.now().minusSeconds(1)));
        assertFalse(login("admin", ADMIN_PASSWORD).isBlank());
        assertEquals(0, jdbc.queryForObject("select failed_attempts from auth_user where username = 'admin'", Integer.class));
    }

    /** 公钥发现不要求登录，并且不暴露 RSA 私钥参数。 */
    @Test void jwksPublishesOnlyPublicMaterial() throws Exception {
        mvc.perform(get("/auth/jwks")).andExpect(status().isOk())
            .andExpect(jsonPath("$.keys[0].n").exists()).andExpect(jsonPath("$.keys[0].d").doesNotExist())
            .andExpect(jsonPath("$.keys[0].p").doesNotExist());
    }

    /** 管理员可以创建操作员，用户列表不得返回密码散列。 */
    @Test void adminCreatesUserWithoutExposingHash() throws Exception {
        String admin = login("admin", ADMIN_PASSWORD);
        mvc.perform(post("/auth/users").header("Authorization", "Bearer " + admin).contentType(MediaType.APPLICATION_JSON)
            .content(mapper.writeValueAsString(new CreateUserRequest("operator", USER_PASSWORD, Role.OPERATOR))))
            .andExpect(status().isCreated()).andExpect(jsonPath("$.data.role").value("OPERATOR"))
            .andExpect(jsonPath("$.data.password_hash").doesNotExist());
        var token = decoder.decode(login("operator", USER_PASSWORD));
        assertTrue(token.getClaimAsStringList("permissions").contains("workorder:write"));
        assertFalse(token.getClaimAsStringList("permissions").contains("user:manage"));
    }

    /** 查看者的真实签名令牌仍不能访问用户管理接口。 */
    @Test void viewerCannotManageUsers() throws Exception {
        users.create(new CreateUserRequest("viewer", USER_PASSWORD, Role.VIEWER));
        String access = login("viewer", USER_PASSWORD);
        mvc.perform(get("/auth/users?page=1").header("Authorization", "Bearer " + access))
            .andExpect(problem(403, "access-denied", "/auth/users"));
        // 未带令牌：401 problem+json，并附 Bearer 质询。
        mvc.perform(get("/auth/me")).andExpect(problem(401, "authentication-required", "/auth/me"))
            .andExpect(header().string("WWW-Authenticate", "Bearer"));
        mvc.perform(post("/auth/users").header("Authorization", "Bearer " + access).contentType(MediaType.APPLICATION_JSON)
            .content(mapper.writeValueAsString(new CreateUserRequest("intruder", USER_PASSWORD, Role.ADMIN))))
            .andExpect(status().isForbidden());
    }

    /** 禁用账号后禁止再次登录；已有令牌的剩余有效期在文档中明确说明。 */
    @Test void disabledUserCannotLogin() throws Exception {
        UserView viewer = users.create(new CreateUserRequest("viewer", USER_PASSWORD, Role.VIEWER));
        String admin = login("admin", ADMIN_PASSWORD);
        mvc.perform(patch("/auth/users/" + viewer.id()).header("Authorization", "Bearer " + admin)
            .contentType(MediaType.APPLICATION_JSON).content("{\"role\":\"VIEWER\",\"enabled\":false}"))
            .andExpect(status().isOk());
        mvc.perform(post("/auth/login").contentType(MediaType.APPLICATION_JSON)
            .content(mapper.writeValueAsString(new LoginRequest("viewer", USER_PASSWORD))))
            .andExpect(status().isUnauthorized());
    }

    /** 不允许管理员通过管理接口禁用自己，避免意外失去所有管理入口。 */
    @Test void administratorCannotLockOutItself() throws Exception {
        String access = login("admin", ADMIN_PASSWORD);
        String id = decoder.decode(access).getSubject();
        mvc.perform(patch("/auth/users/" + id).header("Authorization", "Bearer " + access)
            .contentType(MediaType.APPLICATION_JSON).content("{\"role\":\"VIEWER\",\"enabled\":false}"))
            .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("SELF_LOCKOUT_FORBIDDEN"));
    }

    /** UTF-8 密码超出 BCrypt 字节限制时拒绝，避免静默截断密码。 */
    @Test void rejectsOverlongUtf8Passwords() throws Exception {
        String admin = login("admin", ADMIN_PASSWORD);
        mvc.perform(post("/auth/users").header("Authorization", "Bearer " + admin).contentType(MediaType.APPLICATION_JSON)
            .content(mapper.writeValueAsString(new CreateUserRequest("viewer", "密".repeat(30), Role.VIEWER))))
            .andExpect(status().isBadRequest());
    }

    /** 重读同一持久化文件得到同一公钥，保证认证服务重启不使令牌失效。 */
    @Test void signingKeySurvivesReload() throws Exception {
        SigningKeyConfiguration configuration = new SigningKeyConfiguration();
        RSAKey first = configuration.signingKey(directory.resolve("reload-key.json").toString());
        RSAKey second = configuration.signingKey(directory.resolve("reload-key.json").toString());
        assertEquals(first.toPublicJWK().toJSONString(), second.toPublicJWK().toJSONString());
    }

    /** 断言 RFC 9457 problem+json：Content-Type、五个固定字段齐全，且不再输出旧的 code 字段。 */
    private static org.springframework.test.web.servlet.ResultMatcher problem(int status, String type, String instance) {
        return org.springframework.test.web.servlet.ResultMatcher.matchAll(
            org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().is(status),
            org.springframework.test.web.servlet.result.MockMvcResultMatchers.content().contentTypeCompatibleWith("application/problem+json"),
            org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.type").value(type),
            org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.title").isNotEmpty(),
            org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.status").value(status),
            org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.detail").isNotEmpty(),
            org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.instance").value(instance),
            org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.code").doesNotExist());
    }
}
