package com.smartmanufacturing.quality;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 质检服务错误响应：统一输出 RFC 7807（application/problem+json），业务错误的 type 取自 {@link ProblemType}。
 * <p>
 * 只作用于本服务控制器，并以最高优先级排在 cloud-common 的 ApiExceptionHandler 之前（与库存服务同一做法）。
 * 401/403 由 {@link QualitySecurityConfiguration} 在过滤器层输出同样的格式。
 */
@RestControllerAdvice(basePackageClasses = QualityProblemHandler.class)
@Order(Ordered.HIGHEST_PRECEDENCE)
public class QualityProblemHandler {
    /** RFC 7807 媒体类型。 */
    public static final MediaType PROBLEM_JSON = MediaType.APPLICATION_PROBLEM_JSON;

    @ExceptionHandler(ProblemException.class)
    ResponseEntity<Map<String, Object>> problem(ProblemException e, HttpServletRequest request) {
        Map<String, Object> body = body(e.type().value(), e.type().title(), e.type().status().value(), e.getMessage(), request);
        body.putAll(e.extensions());
        return ResponseEntity.status(e.type().status()).contentType(PROBLEM_JSON).body(body);
    }

    /** JSON 格式错误、缺少必填请求头或查询参数、参数类型不匹配：都属于请求校验失败。 */
    @ExceptionHandler({HttpMessageNotReadableException.class, MissingRequestHeaderException.class,
        MissingServletRequestParameterException.class, MethodArgumentTypeMismatchException.class})
    ResponseEntity<Map<String, Object>> malformed(Exception e, HttpServletRequest request) {
        String detail = e instanceof MissingRequestHeaderException h ? "缺少请求头 " + h.getHeaderName()
            : e instanceof MissingServletRequestParameterException p ? "缺少查询参数 " + p.getParameterName()
            : e instanceof MethodArgumentTypeMismatchException m ? "参数 " + m.getName() + " 格式错误"
            : "请求格式错误";
        return problem(ProblemException.validation(detail), request);
    }

    /** 方法级权限拒绝（@PreAuthorize）：输出 403 problem+json，避免落入通用处理器变成 ApiResponse 或 500。 */
    @ExceptionHandler(org.springframework.security.access.AccessDeniedException.class)
    ResponseEntity<Map<String, Object>> forbidden(Exception e, HttpServletRequest request) {
        return ResponseEntity.status(HttpStatus.FORBIDDEN).contentType(PROBLEM_JSON)
            .body(body("about:blank", "没有访问权限", 403, "没有访问权限", request));
    }

    /** 构造 RFC 7807 基础字段；instance 使用请求路径，便于日志关联。 */
    static Map<String, Object> body(String type, String title, int status, String detail, HttpServletRequest request) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("type", type);
        body.put("title", title);
        body.put("status", status);
        body.put("detail", detail);
        if (request != null) body.put("instance", request.getRequestURI());
        return body;
    }
}
