package com.smartmanufacturing.device;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
/** 使用 H2 验证设备查询、错误响应以及列表端点限流。 */
@SpringBootTest(properties = {"spring.cloud.sentinel.enabled=true", "spring.cloud.sentinel.eager=false", "spring.cloud.sentinel.transport.dashboard="})
@AutoConfigureMockMvc
@ActiveProfiles("test")
class DeviceIntegrationTest {
    @Autowired MockMvc mvc;
    /** 验证种子设备可查询，并保留合法的关联标识。 */
    @Test void seededDeviceAndRequestId() throws Exception {
        mvc.perform(get("/devices/D001").header("X-Request-Id", "test-123"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.data.status").value("ONLINE"))
            .andExpect(header().string("X-Request-Id", "test-123"));
    }
    /** 验证不存在的设备返回明确的 404 业务响应。 */
    @Test void absentDeviceReturns404() throws Exception {
        mvc.perform(get("/devices/absent")).andExpect(status().isNotFound())
            .andExpect(jsonPath("$.code").value("DEVICE_NOT_FOUND"));
    }
    /** 回归验证列表端点也受到 Sentinel 限流保护。 */
    @Test void limitsCollectionEndpoint() throws Exception {
        var rule = new com.alibaba.csp.sentinel.slots.block.flow.FlowRule("/devices");
        rule.setGrade(com.alibaba.csp.sentinel.slots.block.RuleConstant.FLOW_GRADE_QPS);
        rule.setCount(0);
        com.alibaba.csp.sentinel.slots.block.flow.FlowRuleManager.loadRules(java.util.List.of(rule));
        try {
            mvc.perform(get("/devices")).andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.code").value("RATE_LIMITED"));
        } finally {
            com.alibaba.csp.sentinel.slots.block.flow.FlowRuleManager.loadRules(java.util.List.of());
        }
    }
}
