package com.smartmanufacturing.common;
import com.smartmanufacturing.api.ApiResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.*;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.*;
import org.springframework.http.converter.HttpMessageNotReadableException;
/** 统一处理 Servlet 服务异常，将失败映射为对应 HTTP 状态及公共响应体。 */
@RestControllerAdvice
public class ApiExceptionHandler {
    private static final Logger LOG = LoggerFactory.getLogger(ApiExceptionHandler.class);
    /** 保留业务异常的 HTTP 状态及可对外展示的提示；有结构化明细时原样放入 data（如缺料行）。 */
    @ExceptionHandler(BusinessException.class)
    ResponseEntity<ApiResponse<Object>> business(BusinessException e) {
        return ResponseEntity.status(e.status()).body(new ApiResponse<>(e.code(), e.getMessage(), e.details()));
    }
    /** 汇总并排序字段错误，返回稳定的参数校验提示。 */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    ResponseEntity<ApiResponse<Void>> validation(MethodArgumentNotValidException e) {
        String message = e.getBindingResult().getFieldErrors().stream()
            .map(f -> f.getField() + ": " + f.getDefaultMessage()).sorted()
            .collect(java.util.stream.Collectors.joining("; "));
        return ResponseEntity.badRequest().body(ApiResponse.error("INVALID_ARGUMENT", message));
    }
    /** 将 JSON 格式或类型错误转换为 400 响应。 */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    ResponseEntity<ApiResponse<Void>> malformed(HttpMessageNotReadableException e) {
        return ResponseEntity.badRequest().body(ApiResponse.error("INVALID_JSON", "请求体格式错误"));
    }
    /** 方法权限拒绝（@PreAuthorize）必须返回 403 problem+json，与过滤链拒绝格式一致，避免被通用异常处理误判为内部错误。 */
    @ExceptionHandler(org.springframework.security.access.AccessDeniedException.class)
    ResponseEntity<java.util.Map<String, Object>> forbidden(org.springframework.security.access.AccessDeniedException e,
            jakarta.servlet.http.HttpServletRequest request) {
        return SecurityProblem.ACCESS_DENIED.response(request);
    }
    /** 记录内部错误详情，仅向客户端返回统一提示。 */
    @ExceptionHandler(Exception.class)
    ResponseEntity<ApiResponse<Void>> unexpected(Exception e) {
        LOG.error("Unhandled request failure", e);
        return ResponseEntity.internalServerError().body(ApiResponse.error("INTERNAL_ERROR", "服务暂时不可用"));
    }
}
