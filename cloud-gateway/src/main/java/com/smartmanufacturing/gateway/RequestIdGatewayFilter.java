package com.smartmanufacturing.gateway;
import java.util.UUID;
import org.springframework.cloud.gateway.filter.*;
import org.springframework.core.Ordered;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;
/** 在网关入口生成请求标识，同时写入下游请求与客户端响应。 */
@Component
public class RequestIdGatewayFilter implements GlobalFilter, Ordered {
    /** 覆盖外部关联标识并剥离 TX_XID，防止客户端指定内部全局事务身份。 */
    @Override public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        String id = UUID.randomUUID().toString();
        exchange.getResponse().getHeaders().set("X-Request-Id", id);
        return chain.filter(exchange.mutate().request(exchange.getRequest().mutate()
            .headers(headers -> { headers.remove("TX_XID"); headers.set("X-Request-Id", id); }).build()).build());
    }
    /** 在业务路由过滤器之前设置标识，确保下游请求携带该值。 */
    @Override public int getOrder() { return -100; }
}
