package com.smartmanufacturing.workorder;
import com.smartmanufacturing.common.BusinessException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import java.net.URI;

/**
 * 工单服务错误响应：RFC 7807（application/problem+json），type 取自 {@link WorkOrderProblemType}。
 * 为兼容既有调用方，保留扩展字段 code（历史业务码）与 data（结构化明细，如缺料行）。
 * 优先级高于 cloud-common 的通用处理器，只作用于本服务。
 */
@Order(Ordered.HIGHEST_PRECEDENCE)
@RestControllerAdvice
class WorkOrderProblemHandler {
    private static final Logger LOG = LoggerFactory.getLogger(WorkOrderProblemHandler.class);
    private static final MediaType PROBLEM = MediaType.APPLICATION_PROBLEM_JSON;

    @ExceptionHandler(BusinessException.class)
    ResponseEntity<ProblemDetail> business(BusinessException e) {
        WorkOrderProblemType t = WorkOrderProblemType.ofCode(e.code());
        if (t == null) {
            LOG.error("业务码 {} 未在 WorkOrderProblemType 中登记，按内部错误返回", e.code(), e);
            t = WorkOrderProblemType.INTERNAL_ERROR;
        }
        return problem(t, e.getMessage(), e.details());
    }

    /** 参数校验失败统一为 validation-error。 */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    ResponseEntity<ProblemDetail> validation(MethodArgumentNotValidException e) {
        String detail = e.getBindingResult().getFieldErrors().stream()
            .map(f -> f.getField() + ": " + f.getDefaultMessage()).sorted()
            .collect(java.util.stream.Collectors.joining("; "));
        return problem(WorkOrderProblemType.VALIDATION_ERROR, detail, null);
    }

    static ResponseEntity<ProblemDetail> problem(WorkOrderProblemType t, String detail, Object data) {
        ProblemDetail p = ProblemDetail.forStatusAndDetail(t.status(), detail);
        p.setType(URI.create(t.type()));
        p.setTitle(t.name());
        p.setProperty("code", t.code());
        if (data != null) p.setProperty("data", data);
        return ResponseEntity.status(t.status()).contentType(PROBLEM).body(p);
    }
}
