package com.smartmanufacturing.common;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

/**
 * 业务服务（共用 cloud-common 安全配置）与认证服务自身产生的 401/403，统一输出 RFC 9457 problem+json。
 * type 采用相对引用，与工单服务 WorkOrderProblemType 的 authentication-required / access-denied 一致；
 * 网关拦截的同类错误为 gateway-* 前缀，调用方据此区分是网关还是服务本身拒绝。不输出旧的 code 字段。
 */
public enum SecurityProblem {
    /** 未携带令牌，或令牌签名、签发者、受众、有效期校验失败。 */
    AUTHENTICATION_REQUIRED("authentication-required", HttpStatus.UNAUTHORIZED, "需要认证", "请登录或重新获取令牌"),
    /** 身份有效但缺少接口所需权限（过滤链规则或方法级 @PreAuthorize）。 */
    ACCESS_DENIED("access-denied", HttpStatus.FORBIDDEN, "没有访问权限", "当前身份缺少访问该接口所需的权限");

    /** 带 UTF-8 的 problem+json，避免中文 title/detail 在部分客户端按 ISO-8859-1 解码。 */
    public static final MediaType PROBLEM_JSON_UTF8 = MediaType.parseMediaType("application/problem+json;charset=UTF-8");
    private final String type;
    private final HttpStatus status;
    private final String title;
    private final String detail;

    SecurityProblem(String type, HttpStatus status, String title, String detail) {
        this.type = type; this.status = status; this.title = title; this.detail = detail;
    }
    public String type() { return type; }
    public HttpStatus status() { return status; }

    /** 五个固定字段；instance 只取请求路径（getRequestURI 不含查询串），避免回显令牌等查询参数。 */
    public Map<String, Object> body(HttpServletRequest request) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("type", type);
        body.put("title", title);
        body.put("status", status.value());
        body.put("detail", detail);
        body.put("instance", request == null ? null : request.getRequestURI());
        return body;
    }

    /** 安全过滤器中的异常不经过控制器异常处理，在此直接写响应；401 按 RFC 6750 附带 Bearer 质询。 */
    public void write(ObjectMapper mapper, HttpServletRequest request, HttpServletResponse response) throws IOException {
        response.setStatus(status.value());
        response.setContentType(PROBLEM_JSON_UTF8.toString());
        if (status == HttpStatus.UNAUTHORIZED) response.setHeader(HttpHeaders.WWW_AUTHENTICATE, "Bearer");
        mapper.writeValue(response.getWriter(), body(request));
    }

    /** 供控制器异常处理器使用（如方法级权限拒绝）。 */
    public ResponseEntity<Map<String, Object>> response(HttpServletRequest request) {
        var builder = ResponseEntity.status(status).contentType(PROBLEM_JSON_UTF8);
        if (status == HttpStatus.UNAUTHORIZED) builder.header(HttpHeaders.WWW_AUTHENTICATE, "Bearer");
        return builder.body(body(request));
    }
}
