package com.smartmanufacturing.common;
import jakarta.servlet.*;
import jakarta.servlet.http.*;
import java.io.IOException;
import java.util.UUID;
import org.slf4j.MDC;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
/** 校验并传播请求关联标识，使同一次请求在多个服务的日志中可以关联查询。 */
@Component
public class RequestIdFilter extends OncePerRequestFilter {
    public static final String HEADER = "X-Request-Id";
    /** 请求进入时写入 MDC，结束时清理，避免线程复用串联不同请求。 */
    @Override protected void doFilterInternal(HttpServletRequest req, HttpServletResponse res, FilterChain chain)
            throws ServletException, IOException {
        String id = req.getHeader(HEADER);
        // 限制长度和字符集，避免未经校验的请求头污染日志或响应。
        if (id == null || !id.matches("[a-zA-Z0-9-]{1,64}")) id = UUID.randomUUID().toString();
        MDC.put("requestId", id); res.setHeader(HEADER, id);
        try { chain.doFilter(req, res); } finally { MDC.remove("requestId"); }
    }
}
