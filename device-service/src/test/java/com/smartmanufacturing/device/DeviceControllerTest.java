package com.smartmanufacturing.device;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** 设备控制器测试（H2）：补齐列表查询与配置读取的正常路径，保护接口契约与权限边界。 */
@SpringBootTest(properties = "spring.cloud.sentinel.enabled=false")
@AutoConfigureMockMvc
@ActiveProfiles("test")
class DeviceControllerTest {
    @Autowired MockMvc mvc;
    /** 不连接认证服务；令牌由 spring-security-test 直接构造。 */
    @MockitoBean JwtDecoder decoder;

    /** 列表按编号排序返回全部种子设备及状态，工单服务依赖此状态字段。 */
    @Test @DisplayName("设备列表按编号排序返回")
    void listReturnsDevicesOrderedById() throws Exception {
        mvc.perform(get("/devices").with(jwt().authorities(new SimpleGrantedAuthority("device:read"))))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.code").value("OK"))
            .andExpect(jsonPath("$.data[0].id").value("D001"))
            .andExpect(jsonPath("$.data[0].status").value("ONLINE"))
            .andExpect(jsonPath("$.data[1].id").value("D002"))
            .andExpect(jsonPath("$.data[1].status").value("OFFLINE"));
    }

    /** 同时具备查询与配置权限时可读取欢迎配置（未配置时使用默认值）。 */
    @Test @DisplayName("具备 config:read 时可读取配置")
    void configReadableWithPermission() throws Exception {
        mvc.perform(get("/devices/config").with(jwt().authorities(
                new SimpleGrantedAuthority("device:read"), new SimpleGrantedAuthority("config:read"))))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data").isNotEmpty());
    }
}
