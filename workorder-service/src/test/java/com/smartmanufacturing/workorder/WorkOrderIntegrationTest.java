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
    @BeforeEach void clearOrders() { jdbc.update("delete from work_order"); }
    /** 验证在线设备允许创建工单，并实际写入数据库。 */
    @Test void createsAndPersistsOrder() throws Exception {
        when(devices.get("D001")).thenReturn(ApiResponse.ok(new Device("D001", "CNC", "ONLINE")));
        mvc.perform(post("/workorders").contentType(MediaType.APPLICATION_JSON)
            .content("{\"deviceId\":\"D001\",\"product\":\"轴承\",\"quantity\":10}"))
            .andExpect(status().isCreated()).andExpect(jsonPath("$.data.status").value("CREATED"));
        assertEquals(1, jdbc.queryForObject("select count(*) from work_order", Integer.class));
    }
    /** 验证离线设备返回冲突错误，且不产生工单。 */
    @Test void offlineDeviceDoesNotCreateOrder() throws Exception {
        when(devices.get("D002")).thenReturn(ApiResponse.ok(new Device("D002", "robot", "OFFLINE")));
        mvc.perform(post("/workorders").contentType(MediaType.APPLICATION_JSON)
            .content("{\"deviceId\":\"D002\",\"product\":\"轴承\",\"quantity\":10}"))
            .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("DEVICE_OFFLINE"));
        assertEquals(0, jdbc.queryForObject("select count(*) from work_order", Integer.class));
    }
    /** 验证设备服务不可用时返回 503，数据库保持为空。 */
    @Test void unavailableDeviceDoesNotCreateOrder() throws Exception {
        when(devices.get("D001")).thenThrow(new BusinessException("DEVICE_SERVICE_UNAVAILABLE", "unavailable", HttpStatus.SERVICE_UNAVAILABLE));
        mvc.perform(post("/workorders").contentType(MediaType.APPLICATION_JSON)
            .content("{\"deviceId\":\"D001\",\"product\":\"轴承\",\"quantity\":10}"))
            .andExpect(status().isServiceUnavailable());
        assertEquals(0, jdbc.queryForObject("select count(*) from work_order", Integer.class));
    }
    /** 验证非法数量在入口被拒绝，不触发远程调用。 */
    @Test void validatesQuantityBeforeCallingDevice() throws Exception {
        mvc.perform(post("/workorders").contentType(MediaType.APPLICATION_JSON)
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
}
