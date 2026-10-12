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
    /** 报工入口路径：其失败结果计入 mes_workorder_report_total。 */
    private static final java.util.regex.Pattern REPORT_PATH = java.util.regex.Pattern.compile(".*/workorders/[^/]+/operations/[^/]+/reports/?");
    private final ReportingMetrics metrics;
    WorkOrderProblemHandler(ReportingMetrics metrics) { this.metrics = metrics; }

    private void count(jakarta.servlet.http.HttpServletRequest req, WorkOrderProblemType t) {
        if ("POST".equals(req.getMethod()) && REPORT_PATH.matcher(req.getRequestURI()).matches()) metrics.reportProblem(t);
    }

    @ExceptionHandler(BusinessException.class)
    ResponseEntity<ProblemDetail> business(BusinessException e, jakarta.servlet.http.HttpServletRequest req) {
        WorkOrderProblemType t = WorkOrderProblemType.ofCode(e.code());
        count(req, t);
        if (t == null) {
            LOG.error("业务码 {} 未在 WorkOrderProblemType 中登记，按内部错误返回", e.code(), e);
            t = WorkOrderProblemType.INTERNAL_ERROR;
        }
        return problem(t, e.getMessage(), e.details());
    }

    /** 参数校验失败统一为 validation-error。 */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    ResponseEntity<ProblemDetail> validation(MethodArgumentNotValidException e, jakarta.servlet.http.HttpServletRequest req) {
        count(req, WorkOrderProblemType.VALIDATION_ERROR);
        String detail = e.getBindingResult().getFieldErrors().stream()
            .map(f -> f.getField() + ": " + f.getDefaultMessage()).sorted()
            .collect(java.util.stream.Collectors.joining("; "));
        return problem(WorkOrderProblemType.VALIDATION_ERROR, detail, null);
    }

    /** 请求体无法解析（JSON 格式错误、数字非法等）同样为 validation-error。 */
    @ExceptionHandler(org.springframework.http.converter.HttpMessageNotReadableException.class)
    ResponseEntity<ProblemDetail> unreadable(org.springframework.http.converter.HttpMessageNotReadableException e, jakarta.servlet.http.HttpServletRequest req) {
        count(req, WorkOrderProblemType.VALIDATION_ERROR);
        return problem(WorkOrderProblemType.VALIDATION_ERROR, "请求体格式错误", null);
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
