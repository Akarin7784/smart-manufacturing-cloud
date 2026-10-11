package com.smartmanufacturing.gateway;
import com.alibaba.csp.sentinel.adapter.gateway.sc.callback.GatewayCallbackManager;
import com.smartmanufacturing.api.ApiResponse;
import jakarta.annotation.PostConstruct;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.*;
import org.springframework.web.reactive.function.server.ServerResponse;
/** 配置网关专用的 Sentinel 限流响应。 */
@Configuration
public class GatewayConfiguration {
    /** 初始化网关阻断回调，统一返回 RATE_LIMITED 业务码。 */
    @PostConstruct void blockHandler() {
        GatewayCallbackManager.setBlockHandler((exchange, throwable) -> ServerResponse.status(HttpStatus.TOO_MANY_REQUESTS)
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue(ApiResponse.error("RATE_LIMITED", "请求过于频繁，请稍后重试")));
    }
}
