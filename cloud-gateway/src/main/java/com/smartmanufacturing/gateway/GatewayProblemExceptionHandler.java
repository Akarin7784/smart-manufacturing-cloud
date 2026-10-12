package com.smartmanufacturing.gateway;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.ConnectException;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebExceptionHandler;
import reactor.core.publisher.Mono;

/**
 * 把"下游不可用"统一转成 gateway-service-unavailable（503 problem+json）：
 * 负载均衡找不到实例时 Spring Cloud Gateway 抛 503 的 ResponseStatusException，直连地址拒绝连接时抛 ConnectException。
 * 排在 Boot 默认错误处理器（Order -1）之前；其他异常原样交给默认处理器，不吞掉真实错误。
 */
@Component
@Order(-2)
public class GatewayProblemExceptionHandler implements WebExceptionHandler {
    private final ObjectMapper mapper;
    public GatewayProblemExceptionHandler(ObjectMapper mapper) { this.mapper = mapper; }

    @Override public Mono<Void> handle(ServerWebExchange exchange, Throwable ex) {
        if (!isServiceUnavailable(ex) || exchange.getResponse().isCommitted()) return Mono.error(ex);
        return GatewayProblem.SERVICE_UNAVAILABLE.write(mapper, exchange);
    }

    /** 沿 cause 链查找：网关常把连接异常包装一层再抛出。 */
    static boolean isServiceUnavailable(Throwable ex) {
        for (Throwable t = ex; t != null; t = t.getCause()) {
            if (t instanceof ResponseStatusException rse && rse.getStatusCode().value() == HttpStatus.SERVICE_UNAVAILABLE.value()) return true;
            if (t instanceof ConnectException) return true;
            if (t.getCause() == t) break;
        }
        return false;
    }
}
