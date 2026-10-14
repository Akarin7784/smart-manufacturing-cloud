package com.smartmanufacturing.masterdata;
import com.smartmanufacturing.common.BusinessException;
import com.smartmanufacturing.common.SecurityProblem;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.ErrorResponse;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import java.net.URI;
import java.util.Map;

/**
 * 主数据服务错误响应（#71）：RFC 9457 application/problem+json，type 取自 {@link MasterdataProblemType}，
 * 字段写法与工单服务 WorkOrderProblemHandler 完全一致：type / title / status / detail / instance（instance 由 Spring 按请求路径填充），扩展字段 code（历史业务码）与 data（结构化明细）。
 * 优先级高于 cloud-common 的通用 ApiResponse 处理器，只作用于本服务；方法级 403 仍按 SecurityProblem 输出。
 */
@Order(Ordered.HIGHEST_PRECEDENCE)
@RestControllerAdvice
class MasterdataProblemHandler {
    private static final Logger LOG = LoggerFactory.getLogger(MasterdataProblemHandler.class);
    private static final MediaType PROBLEM = MediaType.APPLICATION_PROBLEM_JSON;

    @ExceptionHandler(BusinessException.class)
    ResponseEntity<ProblemDetail> business(BusinessException e, HttpServletRequest req) {
        MasterdataProblemType t = MasterdataProblemType.ofCode(e.code());
        if (t == null) {
            LOG.error("业务码 {} 未在 MasterdataProblemType 中登记，按内部错误返回", e.code(), e);
            return problem(MasterdataProblemType.INTERNAL_ERROR, HttpStatus.INTERNAL_SERVER_ERROR, "服务暂时不可用", null, req);
        }
        return problem(t, e.status(), e.getMessage(), e.details(), req);
    }

    /** 参数校验失败统一为 validation-error（detail 为按字段排序的 "field: 消息; ..."，与工单服务一致）。 */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    ResponseEntity<ProblemDetail> validation(MethodArgumentNotValidException e, HttpServletRequest req) {
        String detail = e.getBindingResult().getFieldErrors().stream()
            .map(f -> f.getField() + ": " + f.getDefaultMessage()).sorted()
            .collect(java.util.stream.Collectors.joining("; "));
        return problem(MasterdataProblemType.VALIDATION_ERROR, HttpStatus.BAD_REQUEST, detail, null, req);
    }

    /** 请求体无法解析同样为 validation-error。 */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    ResponseEntity<ProblemDetail> unreadable(HttpMessageNotReadableException e, HttpServletRequest req) {
        return problem(MasterdataProblemType.VALIDATION_ERROR, HttpStatus.BAD_REQUEST, "请求体格式错误", null, req);
    }

    /** 查询参数缺失或类型错误（如 date=abc）：validation-error。 */
    @ExceptionHandler({MissingServletRequestParameterException.class, MethodArgumentTypeMismatchException.class})
    ResponseEntity<ProblemDetail> badParameter(Exception e, HttpServletRequest req) {
        String name = e instanceof MissingServletRequestParameterException m ? m.getParameterName() : ((MethodArgumentTypeMismatchException) e).getName();
        return problem(MasterdataProblemType.VALIDATION_ERROR, HttpStatus.BAD_REQUEST, name + ": 参数缺失或格式错误", null, req);
    }

    /** 方法参数约束（如 quantity 的 @DecimalMin）失败：validation-error。 */
    @ExceptionHandler({org.springframework.web.method.annotation.HandlerMethodValidationException.class, jakarta.validation.ConstraintViolationException.class})
    ResponseEntity<ProblemDetail> constraint(Exception e, HttpServletRequest req) {
        return problem(MasterdataProblemType.VALIDATION_ERROR, HttpStatus.BAD_REQUEST, "请求参数不符合约束", null, req);
    }

    /** 方法级权限拒绝：与过滤链一致的 403 problem+json。 */
    @ExceptionHandler(org.springframework.security.access.AccessDeniedException.class)
    ResponseEntity<Map<String, Object>> forbidden(org.springframework.security.access.AccessDeniedException e, HttpServletRequest req) {
        return SecurityProblem.ACCESS_DENIED.response(req);
    }

    /** 其余异常：Spring MVC 自带状态的（404 无路由、405 等）保留状态，其他按 500 internal-error，不泄露内部信息。 */
    @ExceptionHandler(Exception.class)
    ResponseEntity<ProblemDetail> unexpected(Exception e, HttpServletRequest req) {
        if (e instanceof ErrorResponse er && er.getStatusCode().is4xxClientError()) {
            HttpStatusCode s = er.getStatusCode();
            ProblemDetail p = ProblemDetail.forStatusAndDetail(s, er.getBody().getDetail());
            return ResponseEntity.status(s).contentType(PROBLEM).body(p);
        }
        LOG.error("Unhandled request failure", e);
        return problem(MasterdataProblemType.INTERNAL_ERROR, HttpStatus.INTERNAL_SERVER_ERROR, "服务暂时不可用", null, req);
    }

    static ResponseEntity<ProblemDetail> problem(MasterdataProblemType t, HttpStatusCode status, String detail, Object data, HttpServletRequest req) {
        ProblemDetail p = ProblemDetail.forStatusAndDetail(status, detail);
        p.setType(URI.create(t.type()));
        p.setTitle(t.title());
        p.setProperty("code", t.code());
        if (data != null) p.setProperty("data", data);
        return ResponseEntity.status(status).contentType(PROBLEM).body(p);
    }

}
