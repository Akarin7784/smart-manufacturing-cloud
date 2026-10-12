package com.smartmanufacturing.workorder;
import com.smartmanufacturing.api.*;
import com.smartmanufacturing.common.BusinessException;
import feign.*;
import java.util.UUID;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.http.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import static org.mockito.Mockito.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
/** 模拟设备调用，验证工单持久化及失败时不写入数据库。 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class WorkOrderIntegrationTest {
    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @MockitoBean DeviceClient devices;
    @MockitoBean org.springframework.security.oauth2.jwt.JwtDecoder decoder;

    /** 操作员拥有工单创建权限，原业务回归在此身份下运行。 */
    private org.springframework.test.web.servlet.request.RequestPostProcessor operator() {
        return org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt()
            .authorities(new org.springframework.security.core.authority.SimpleGrantedAuthority("workorder:write"));
    }
    @BeforeEach void clearOrders() { jdbc.update("delete from work_order_request"); jdbc.update("delete from work_order"); }
    /** 验证在线设备允许创建工单，并实际写入数据库。 */
    @Test void createsAndPersistsOrder() throws Exception {
        when(devices.get("D001")).thenReturn(ApiResponse.ok(new Device("D001", "CNC", "ONLINE")));
        mvc.perform(post("/workorders").with(operator()).header("Idempotency-Key", UUID.randomUUID().toString()).contentType(MediaType.APPLICATION_JSON)
            .content("{\"deviceId\":\"D001\",\"product\":\"轴承\",\"quantity\":10}"))
            .andExpect(status().isCreated()).andExpect(jsonPath("$.data.status").value("CREATED"));
        assertEquals(1, jdbc.queryForObject("select count(*) from work_order", Integer.class));
    }
    /** 验证离线设备返回冲突错误，且不产生工单。 */
    @Test void offlineDeviceDoesNotCreateOrder() throws Exception {
        when(devices.get("D002")).thenReturn(ApiResponse.ok(new Device("D002", "robot", "OFFLINE")));
        mvc.perform(post("/workorders").with(operator()).header("Idempotency-Key", UUID.randomUUID().toString()).contentType(MediaType.APPLICATION_JSON)
            .content("{\"deviceId\":\"D002\",\"product\":\"轴承\",\"quantity\":10}"))
            .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("DEVICE_OFFLINE"));
        assertEquals(0, jdbc.queryForObject("select count(*) from work_order", Integer.class));
    }
    /** 验证设备服务不可用时返回 503，数据库保持为空。 */
    @Test void unavailableDeviceDoesNotCreateOrder() throws Exception {
        when(devices.get("D001")).thenThrow(new BusinessException("DEVICE_SERVICE_UNAVAILABLE", "unavailable", HttpStatus.SERVICE_UNAVAILABLE));
        mvc.perform(post("/workorders").with(operator()).header("Idempotency-Key", UUID.randomUUID().toString()).contentType(MediaType.APPLICATION_JSON)
            .content("{\"deviceId\":\"D001\",\"product\":\"轴承\",\"quantity\":10}"))
            .andExpect(status().isServiceUnavailable());
        assertEquals(0, jdbc.queryForObject("select count(*) from work_order", Integer.class));
    }
    /** 验证非法数量在入口被拒绝，不触发远程调用。 */
    @Test void validatesQuantityBeforeCallingDevice() throws Exception {
        mvc.perform(post("/workorders").with(operator()).header("Idempotency-Key", UUID.randomUUID().toString()).contentType(MediaType.APPLICATION_JSON)
            .content("{\"deviceId\":\"D001\",\"product\":\"轴承\",\"quantity\":0}"))
            .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_ARGUMENT"));
        verifyNoInteractions(devices);
    }
    /** 验证降级区分设备不存在和服务故障，不返回伪造设备。 */
    @Test void fallbackMapsFailuresWithoutFabricatingDevices() {
        DeviceFallbackFactory fallback = new DeviceFallbackFactory();
        BusinessException e = assertThrows(BusinessException.class, () -> fallback.create(new RuntimeException("timeout")).get("D001"));
        assertEquals(HttpStatus.SERVICE_UNAVAILABLE, e.status());
        Request req = Request.create(Request.HttpMethod.GET, "/devices/missing", java.util.Map.of(), null, java.nio.charset.StandardCharsets.UTF_8, null);
        BusinessException missing = assertThrows(BusinessException.class, () -> fallback.create(
            new FeignException.NotFound("missing", req, null, java.util.Map.of())).get("missing"));
        assertEquals(HttpStatus.NOT_FOUND, missing.status());
    }
    /** 查看者禁止创建工单，拒绝发生在远程设备调用和数据库写入之前。 */
    @Test void viewerCannotCreateOrders() throws Exception {
        // 携带合法幂等键，证明拒绝来自权限校验而非缺键分支。
        mvc.perform(post("/workorders").header("Idempotency-Key", "viewer-key")
            .with(org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt()
                .authorities(new org.springframework.security.core.authority.SimpleGrantedAuthority("workorder:read")))
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"deviceId\":\"D001\",\"product\":\"bearing\",\"quantity\":10}"))
            .andExpect(problem(403, "access-denied", "/workorders"));
        verifyNoInteractions(devices);
        assertEquals(0, jdbc.queryForObject("select count(*) from work_order", Integer.class));
    }

    /** 直连工单服务不能绕过认证，伪造角色请求头也不会授予身份。 */
    @Test void forgedRoleHeaderDoesNotAuthenticate() throws Exception {
        mvc.perform(post("/workorders").header("X-Role", "ADMIN").contentType(MediaType.APPLICATION_JSON)
            .content("{\"deviceId\":\"D001\",\"product\":\"bearing\",\"quantity\":10}"))
            .andExpect(status().isUnauthorized());
        verifyNoInteractions(devices);
    }

    /** Feign 只从已验证的安全上下文传播令牌，调用结束后清理测试上下文。 */
    @Test void feignPropagatesVerifiedBearerToken() {
        var token = org.springframework.security.oauth2.jwt.Jwt.withTokenValue("verified-token")
            .header("alg", "RS256").subject("operator").build();
        var context = org.springframework.security.core.context.SecurityContextHolder.getContext();
        context.setAuthentication(new org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken(token));
        try {
            RequestTemplate template = new RequestTemplate();
            new FeignConfiguration().requestIdInterceptor().apply(template);
            assertEquals(java.util.List.of("Bearer verified-token"), new java.util.ArrayList<>(template.headers().get("Authorization")));
        } finally {
            org.springframework.security.core.context.SecurityContextHolder.clearContext();
        }
    }

    /** 按指定 subject 构造操作员令牌，用于验证幂等键按调用方隔离。 */
    private org.springframework.test.web.servlet.request.RequestPostProcessor operatorAs(String subject) {
        return org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt()
            .jwt(j -> j.subject(subject))
            .authorities(new org.springframework.security.core.authority.SimpleGrantedAuthority("workorder:write"));
    }
    private static final String BODY = "{\"deviceId\":\"D001\",\"product\":\"减速机\",\"quantity\":5}";
    private void deviceOnline() {
        when(devices.get("D001")).thenReturn(ApiResponse.ok(new Device("D001", "CNC", "ONLINE")));
    }

    /** 缺少幂等键时在远程调用与写库之前返回 400，防止产生无法去重的工单。 */
    @Test void missingIdempotencyKeyIsRejected() throws Exception {
        mvc.perform(post("/workorders").with(operator()).contentType(MediaType.APPLICATION_JSON).content(BODY))
            .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("IDEMPOTENCY_KEY_REQUIRED"));
        verifyNoInteractions(devices);
        assertEquals(0, jdbc.queryForObject("select count(*) from work_order", Integer.class));
    }

    /** 同键同内容重放：返回 200 与首次工单，只写一条，且重放不再调用设备服务。 */
    @Test void replayReturnsOriginalOrder() throws Exception {
        deviceOnline();
        String first = mvc.perform(post("/workorders").with(operator()).header("Idempotency-Key", "K-1")
                .contentType(MediaType.APPLICATION_JSON).content(BODY))
            .andExpect(status().isCreated()).andExpect(header().string("Idempotent-Replayed", "false"))
            .andReturn().getResponse().getContentAsString();
        String id = com.jayway.jsonpath.JsonPath.read(first, "$.data.id");
        mvc.perform(post("/workorders").with(operator()).header("Idempotency-Key", "K-1")
                .contentType(MediaType.APPLICATION_JSON).content(BODY))
            .andExpect(status().isOk()).andExpect(header().string("Idempotent-Replayed", "true"))
            .andExpect(jsonPath("$.data.id").value(id));
        verify(devices, times(1)).get("D001");
        assertEquals(1, jdbc.queryForObject("select count(*) from work_order", Integer.class));
    }

    /** 同键不同内容视为客户端错误返回 422，不覆盖也不新建工单。 */
    @Test void reusedKeyWithDifferentBodyIsRejected() throws Exception {
        deviceOnline();
        mvc.perform(post("/workorders").with(operator()).header("Idempotency-Key", "K-2")
            .contentType(MediaType.APPLICATION_JSON).content(BODY)).andExpect(status().isCreated());
        mvc.perform(post("/workorders").with(operator()).header("Idempotency-Key", "K-2")
                .contentType(MediaType.APPLICATION_JSON).content(BODY.replace("5", "6")))
            .andExpect(status().isUnprocessableEntity()).andExpect(jsonPath("$.code").value("IDEMPOTENCY_KEY_REUSED"));
        assertEquals(1, jdbc.queryForObject("select count(*) from work_order", Integer.class));
    }

    /** 幂等键按调用方隔离：不同用户使用相同键不会拿到他人的工单。 */
    @Test void sameKeyDifferentPrincipalsCreateSeparateOrders() throws Exception {
        deviceOnline();
        for (String user : new String[] { "alice", "bob" })
            mvc.perform(post("/workorders").with(operatorAs(user)).header("Idempotency-Key", "K-3")
                .contentType(MediaType.APPLICATION_JSON).content(BODY)).andExpect(status().isCreated());
        assertEquals(2, jdbc.queryForObject("select count(*) from work_order", Integer.class));
    }

    /**
     * 模拟并发竞争：在本请求完成设备校验后、写库前，另一请求已用同键提交成功。
     * 期望唯一约束裁决后本请求整笔回滚并返回胜者工单，库中只有一条工单。
     * 真实多线程 + MySQL 的并发用例见 PR 说明中的 Testcontainers TODO。
     */
    @Test void concurrentDuplicateResolvesToWinner() throws Exception {
        String winner = "winner-order";
        when(devices.get("D001")).thenAnswer(inv -> {
            jdbc.update("insert into work_order (id, device_id, product, quantity, status) values (?, 'D001', '减速机', 5, 'CREATED')", winner);
            jdbc.update("insert into work_order_request (principal, idempotency_key, request_hash, order_id) values ('user', 'K-4', ?, ?)",
                WorkOrderService.hash(new CreateWorkOrder("D001", "减速机", 5)), winner);
            return ApiResponse.ok(new Device("D001", "CNC", "ONLINE"));
        });
        mvc.perform(post("/workorders").with(operator()).header("Idempotency-Key", "K-4")
                .contentType(MediaType.APPLICATION_JSON).content(BODY))
            .andExpect(status().isOk()).andExpect(jsonPath("$.data.id").value(winner));
        assertEquals(1, jdbc.queryForObject("select count(*) from work_order", Integer.class));
    }

    /** 非法幂等键（超过 128 位、含空格）在远程调用前返回 400。 */
    @Test void malformedIdempotencyKeyIsRejected() throws Exception {
        for (String key : new String[] { "a".repeat(129), "has space" })
            mvc.perform(post("/workorders").with(operator()).header("Idempotency-Key", key)
                    .contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("IDEMPOTENCY_KEY_REQUIRED"));
        verifyNoInteractions(devices);
    }

    /** 规范化后等价的请求（产品名首尾空白、NFC 等价形式）视为同一请求重放，而不是 422。 */
    @Test void equivalentBodiesAreTreatedAsReplay() throws Exception {
        deviceOnline();
        mvc.perform(post("/workorders").with(operator()).header("Idempotency-Key", "K-5")
            .contentType(MediaType.APPLICATION_JSON).content(BODY)).andExpect(status().isCreated());
        mvc.perform(post("/workorders").with(operator()).header("Idempotency-Key", "K-5")
                .contentType(MediaType.APPLICATION_JSON).content(BODY.replace("减速机", " 减速机 ")))
            .andExpect(status().isOk()).andExpect(header().string("Idempotent-Replayed", "true"));
        // e + 组合重音（NFD）与 é（NFC）规范化后相同。
        org.junit.jupiter.api.Assertions.assertEquals(
            WorkOrderService.hash(WorkOrderService.normalize(new CreateWorkOrder("D001", "caf\u00e9", 1))),
            WorkOrderService.hash(WorkOrderService.normalize(new CreateWorkOrder("D001", "cafe\u0301", 1))));
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
