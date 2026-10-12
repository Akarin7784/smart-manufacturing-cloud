package com.smartmanufacturing.workorder;
import com.smartmanufacturing.api.*;
import com.smartmanufacturing.common.BusinessException;
import feign.*;
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
    @BeforeEach void clearOrders() { jdbc.update("delete from outbox_event"); jdbc.update("delete from work_order"); }
    /** 验证在线设备允许创建工单，并实际写入数据库。 */
    @Test void createsAndPersistsOrder() throws Exception {
        when(devices.get("D001")).thenReturn(ApiResponse.ok(new Device("D001", "CNC", "ONLINE")));
        mvc.perform(post("/workorders").with(operator()).contentType(MediaType.APPLICATION_JSON)
            .content("{\"deviceId\":\"D001\",\"product\":\"轴承\",\"quantity\":10}"))
            .andExpect(status().isCreated()).andExpect(jsonPath("$.data.status").value("CREATED"));
        assertEquals(1, jdbc.queryForObject("select count(*) from work_order", Integer.class));
        assertEquals(1, jdbc.queryForObject("select count(*) from outbox_event where status = 'PENDING'", Integer.class));
    }
    /** 验证离线设备返回冲突错误，且不产生工单。 */
    @Test void offlineDeviceDoesNotCreateOrder() throws Exception {
        when(devices.get("D002")).thenReturn(ApiResponse.ok(new Device("D002", "robot", "OFFLINE")));
        mvc.perform(post("/workorders").with(operator()).contentType(MediaType.APPLICATION_JSON)
            .content("{\"deviceId\":\"D002\",\"product\":\"轴承\",\"quantity\":10}"))
            .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("DEVICE_OFFLINE"));
        assertEquals(0, jdbc.queryForObject("select count(*) from work_order", Integer.class));
    }
    /** 验证设备服务不可用时返回 503，数据库保持为空。 */
    @Test void unavailableDeviceDoesNotCreateOrder() throws Exception {
        when(devices.get("D001")).thenThrow(new BusinessException("DEVICE_SERVICE_UNAVAILABLE", "unavailable", HttpStatus.SERVICE_UNAVAILABLE));
        mvc.perform(post("/workorders").with(operator()).contentType(MediaType.APPLICATION_JSON)
            .content("{\"deviceId\":\"D001\",\"product\":\"轴承\",\"quantity\":10}"))
            .andExpect(status().isServiceUnavailable());
        assertEquals(0, jdbc.queryForObject("select count(*) from work_order", Integer.class));
    }
    /** 验证非法数量在入口被拒绝，不触发远程调用。 */
    @Test void validatesQuantityBeforeCallingDevice() throws Exception {
        mvc.perform(post("/workorders").with(operator()).contentType(MediaType.APPLICATION_JSON)
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
        mvc.perform(post("/workorders")
            .with(org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt()
                .authorities(new org.springframework.security.core.authority.SimpleGrantedAuthority("workorder:read")))
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"deviceId\":\"D001\",\"product\":\"bearing\",\"quantity\":10}"))
            .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("ACCESS_DENIED"));
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
}
