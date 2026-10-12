package com.smartmanufacturing.gateway;
import com.alibaba.csp.sentinel.adapter.gateway.sc.callback.GatewayCallbackManager;
import jakarta.annotation.PostConstruct;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.reactive.function.server.ServerResponse;
/** 配置网关专用的 Sentinel 限流响应。 */
@Configuration
public class GatewayConfiguration {
    /** 初始化网关阻断回调：返回 429 problem+json（type=gateway-rate-limited）。 */
    @PostConstruct void blockHandler() {
        GatewayCallbackManager.setBlockHandler((exchange, throwable) -> ServerResponse.status(GatewayProblem.RATE_LIMITED.status())
            .contentType(GatewayProblem.PROBLEM_JSON)
            .bodyValue(GatewayProblem.RATE_LIMITED.body(exchange.getRequest().getPath().value())));
    }
}
