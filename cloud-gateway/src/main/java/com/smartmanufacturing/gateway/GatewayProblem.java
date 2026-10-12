package com.smartmanufacturing.gateway;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

/**
 * 网关自身产生的错误类型（RFC 9457 problem+json）。
 * type 与各业务服务一致采用相对引用（如质检的 over-dispose、工单的 over-report），统一加 gateway- 前缀，
 * 调用方可据此区分"网关拦截"与"业务服务拒绝"；响应只含 type/title/status/detail/instance，不再输出旧的 code 字段。
 */
public enum GatewayProblem {
    /** 未携带令牌，或令牌签名、签发者、受众、有效期校验失败。 */
    AUTHENTICATION_REQUIRED("gateway-authentication-required", HttpStatus.UNAUTHORIZED, "需要认证", "请登录或重新获取令牌"),
    /** 身份有效但缺少该接口所需权限，或接口不对网关开放。 */
    ACCESS_DENIED("gateway-access-denied", HttpStatus.FORBIDDEN, "没有访问权限", "当前身份缺少访问该接口所需的权限"),
    /** 命中 Sentinel 网关限流规则。 */
    RATE_LIMITED("gateway-rate-limited", HttpStatus.TOO_MANY_REQUESTS, "请求过于频繁", "请求过于频繁，请稍后重试"),
    /** 路由的下游服务没有可用实例或无法连接。 */
    SERVICE_UNAVAILABLE("gateway-service-unavailable", HttpStatus.SERVICE_UNAVAILABLE, "下游服务不可用", "目标服务暂时不可用，请稍后重试");

    public static final MediaType PROBLEM_JSON = MediaType.APPLICATION_PROBLEM_JSON;
    private final String type;
    private final HttpStatus status;
    private final String title;
    private final String detail;

    GatewayProblem(String type, HttpStatus status, String title, String detail) {
        this.type = type; this.status = status; this.title = title; this.detail = detail;
    }
    public String type() { return type; }
    public HttpStatus status() { return status; }

    /** 生成响应体；字段顺序固定，instance 取请求路径（不含查询串，避免回显令牌等参数）。 */
    public Map<String, Object> body(String instance) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("type", type);
        body.put("title", title);
        body.put("status", status.value());
        body.put("detail", detail);
        body.put("instance", instance);
        return body;
    }

    /** 直接写入响应：用于安全过滤链与异常处理器这类拿不到 ServerResponse 的位置。 */
    public Mono<Void> write(ObjectMapper mapper, ServerWebExchange exchange) {
        var response = exchange.getResponse();
        response.setStatusCode(status);
        response.getHeaders().setContentType(PROBLEM_JSON);
        // RFC 6750：401 必须带 WWW-Authenticate 质询。
        if (status == HttpStatus.UNAUTHORIZED) response.getHeaders().set(HttpHeaders.WWW_AUTHENTICATE, "Bearer");
        try {
            byte[] bytes = mapper.writeValueAsBytes(body(exchange.getRequest().getPath().value()));
            return response.writeWith(Mono.just(response.bufferFactory().wrap(bytes)));
        } catch (java.io.IOException e) {
            return Mono.error(e);
        }
    }
}
