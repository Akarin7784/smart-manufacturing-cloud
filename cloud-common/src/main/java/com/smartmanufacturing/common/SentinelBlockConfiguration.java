package com.smartmanufacturing.common;
import com.alibaba.csp.sentinel.adapter.spring.webmvc_v6x.callback.BlockExceptionHandler;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.smartmanufacturing.api.ApiResponse;
import org.springframework.context.annotation.*;
/** 配置 Servlet 接口的 Sentinel 阻断响应，统一限流错误格式。 */
@Configuration
public class SentinelBlockConfiguration {
    /** 使用 Spring 6 适配接口，将限流阻断转换为 JSON 格式的 429 响应。 */
    @Bean BlockExceptionHandler blockExceptionHandler(ObjectMapper mapper) {
        return (request, response, resourceName, exception) -> {
            response.setStatus(429);
            response.setContentType("application/json;charset=UTF-8");
            mapper.writeValue(response.getWriter(), ApiResponse.error("RATE_LIMITED", "请求过于频繁，请稍后重试"));
        };
    }
}
