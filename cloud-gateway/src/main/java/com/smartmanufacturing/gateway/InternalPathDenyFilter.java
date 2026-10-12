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
        return hasDotSegmentOrBackslash(raw) || isInternal(raw) ? notFound(exchange) : chain.filter(exchange);
    }

    /**
     * 全网关拒绝含点段（{@code .}、{@code ..}）、反斜杠或双重编码的路径，不只针对内部清单。
     * <p>原因：网关按原样转发路径，而下游 Tomcat/Spring 会再做一次规范化，{@code /api/inventory/x/../qc-hold/receipts}
     * 在网关看来不在清单内，到下游却变成 {@code /inventory/qc-hold/receipts}，绕过清单与网关权限规则。
     * 与其让网关精确模拟下游的规范化算法（各容器对 {@code ;}、编码点、反斜杠的处理并不一致），
     * 不如直接拒绝：正常客户端从不发送点段或反斜杠，拒绝它们没有业务代价。</p>
     * <p>判定方式：只解码一次（覆盖 {@code %2e}、{@code %2E}、{@code .%2e}、{@code %5C}）。解码后仍含 {@code %}
     * 说明是双重编码（如 {@code %252e}），正常客户端不会这样发送，直接拒绝，避免网关与下游解码次数不一致。
     * 原始路径与解码后路径都检查反斜杠，并按 {@code /} 与反斜杠切段、去掉各段 {@code ;} 参数后看是否有
     * {@code .} 或 {@code ..}（覆盖 {@code ..;/}）。返回与内部路径相同的 404 gateway-not-found，不暴露拒绝原因。</p>
     */
    static boolean hasDotSegmentOrBackslash(String rawPath) {
        String decoded;
        try { decoded = URLDecoder.decode(rawPath.replace("+", "%2B"), StandardCharsets.UTF_8); }
        catch (IllegalArgumentException e) { return true; } // 非法百分号编码同样拒绝，下游行为不可预期
        if (decoded.indexOf('%') >= 0) return true; // 双重编码
        for (String path : new String[] { rawPath, decoded }) {
            if (path.indexOf('\\') >= 0) return true;
            for (String segment : path.split("[/\\\\]", -1)) {
                String name = segment.contains(";") ? segment.substring(0, segment.indexOf(';')) : segment;
                if (name.equals(".") || name.equals("..")) return true;
            }
        }
        return false;
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
