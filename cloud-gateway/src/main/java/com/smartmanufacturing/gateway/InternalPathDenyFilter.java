package com.smartmanufacturing.gateway;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.core.env.Environment;
import org.springframework.http.server.PathContainer;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import org.springframework.web.util.pattern.PathPattern;
import org.springframework.web.util.pattern.PathPatternParser;
import reactor.core.publisher.Mono;

/**
 * 拒绝经网关访问服务间内部接口（清单见 application.yml 的 {@code manufacturing.gateway.internal-paths}）。
 * <p>这些接口只接受服务令牌（aud=inventory-service 等），调用方经 Nacos 直连资源服务，网关不应转发。</p>
 * <ul>
 *   <li>返回 404 而不是 401/403：无论是否带令牌、令牌权限如何，响应完全一致，不暴露接口存在。</li>
 *   <li>独立 WebFilter，排在 Spring Security 过滤链（order -100）之前：无令牌请求也不会先得到 401；
 *       错误体复用 {@link GatewayProblem#NOT_FOUND}，与网关其他错误同一格式。</li>
 *   <li>匹配前先规范化路径（URL 解码、合并重复斜杠、去掉矩阵参数），防止用 {@code %2F}、{@code //}、{@code ;x}
 *       等变体绕过；原始路径与规范化路径任一命中即拒绝。</li>
 * </ul>
 */
@Component
@Order(InternalPathDenyFilter.ORDER)
public class InternalPathDenyFilter implements WebFilter {
    /** 早于 Spring Security 的 WebFilterChainProxy（-100），保证认证前即拒绝。 */
    static final int ORDER = Ordered.HIGHEST_PRECEDENCE + 10;

    /** 清单条目：路径模式与说明（说明只用于配置可读性和日志，不输出给调用方）。 */
    public record InternalPath(String pattern, String reason) { }

    private final List<PathPattern> denied;
    private final ObjectMapper mapper;

    public InternalPathDenyFilter(Environment environment, ObjectMapper mapper) {
        this.mapper = mapper;
        List<InternalPath> entries = Binder.get(environment)
            .bind("manufacturing.gateway.internal-paths", Bindable.listOf(InternalPath.class)).orElse(List.of());
        // 清单被误删时宁可启动失败，也不能静默把内部接口暴露到网关。
        if (entries.isEmpty()) throw new IllegalStateException("manufacturing.gateway.internal-paths 不能为空");
        this.denied = entries.stream().map(e -> PathPatternParser.defaultInstance.parse(e.pattern())).toList();
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        String raw = exchange.getRequest().getPath().pathWithinApplication().value();
        return isInternal(raw) ? notFound(exchange) : chain.filter(exchange);
    }

    /** 原始路径或规范化后的路径命中任一内部模式即视为内部接口。 */
    boolean isInternal(String rawPath) {
        PathContainer original = PathContainer.parsePath(rawPath);
        PathContainer normalized = PathContainer.parsePath(normalize(rawPath));
        return denied.stream().anyMatch(p -> p.matches(original) || p.matches(normalized));
    }

    /** 解码百分号编码（含 %2F、%3B）、去掉各段矩阵参数、合并重复斜杠并去掉尾部斜杠。 */
    static String normalize(String rawPath) {
        String decoded;
        try { decoded = URLDecoder.decode(rawPath.replace("+", "%2B"), StandardCharsets.UTF_8); }
        catch (IllegalArgumentException e) { decoded = rawPath; }
        String noMatrix = decoded.replaceAll(";[^/]*", "");
        String collapsed = noMatrix.replaceAll("/{2,}", "/");
        return collapsed.length() > 1 && collapsed.endsWith("/") ? collapsed.substring(0, collapsed.length() - 1) : collapsed;
    }

    /** 复用网关统一的 problem+json 写法（GatewayProblem），type=gateway-not-found，instance 为不含查询串的路径。 */
    private Mono<Void> notFound(ServerWebExchange exchange) {
        return GatewayProblem.NOT_FOUND.write(mapper, exchange);
    }
}
