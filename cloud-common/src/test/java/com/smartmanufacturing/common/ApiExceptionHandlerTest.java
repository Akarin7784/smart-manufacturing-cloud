package com.smartmanufacturing.common;

import com.smartmanufacturing.api.ApiResponse;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.core.MethodParameter;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.http.server.ServletServerHttpRequest;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.validation.BeanPropertyBindingResult;
import org.springframework.web.bind.MethodArgumentNotValidException;

import static org.assertj.core.api.Assertions.assertThat;

/** ApiExceptionHandler 单元测试：保护各类异常到 HTTP 状态与统一响应体的映射，防止客户端错误被误报为 500。 */
class ApiExceptionHandlerTest {
    private final ApiExceptionHandler handler = new ApiExceptionHandler();

    /** 业务异常必须原样保留业务码、提示和 HTTP 状态。 */
    @Test @DisplayName("业务异常保留业务码与状态")
    void businessExceptionKeepsCodeAndStatus() {
        ResponseEntity<ApiResponse<Void>> r = handler.business(
            new BusinessException("DEVICE_OFFLINE", "设备离线，无法创建工单", HttpStatus.CONFLICT));
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(r.getBody()).isEqualTo(ApiResponse.error("DEVICE_OFFLINE", "设备离线，无法创建工单"));
    }

    /** 多个字段错误按字典序拼接，保证提示稳定、便于前端和测试断言。 */
    @Test @DisplayName("参数校验错误排序后返回 400")
    void validationErrorsAreSortedAndReturn400() throws Exception {
        var binding = new BeanPropertyBindingResult(new Object(), "request");
        binding.addError(new org.springframework.validation.FieldError("request", "quantity", "必须大于0"));
        binding.addError(new org.springframework.validation.FieldError("request", "deviceId", "不能为空"));
        MethodParameter parameter = new MethodParameter(Object.class.getMethod("toString"), -1);
        ResponseEntity<ApiResponse<Void>> r = handler.validation(new MethodArgumentNotValidException(parameter, binding));
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(r.getBody().code()).isEqualTo("INVALID_ARGUMENT");
        assertThat(r.getBody().message()).isEqualTo("deviceId: 不能为空; quantity: 必须大于0");
    }

    /** JSON 解析失败属于客户端错误，返回 400 且不泄露解析细节。 */
    @Test @DisplayName("请求体格式错误返回 400")
    void malformedJsonReturns400() {
        var ex = new HttpMessageNotReadableException("Unexpected token", new ServletServerHttpRequest(new MockHttpServletRequest()));
        ResponseEntity<ApiResponse<Void>> r = handler.malformed(ex);
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(r.getBody()).isEqualTo(ApiResponse.error("INVALID_JSON", "请求体格式错误"));
    }

    /** 方法级权限拒绝必须是 403，不能落入通用 500 分支。 */
    @Test @DisplayName("权限拒绝返回 403")
    void accessDeniedReturns403() {
        ResponseEntity<ApiResponse<Void>> r = handler.forbidden(new AccessDeniedException("denied"));
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(r.getBody().code()).isEqualTo("ACCESS_DENIED");
    }

    /** 未知异常只返回统一提示，内部异常信息不能出现在响应体中。 */
    @Test @DisplayName("未知异常返回 500 且不泄露细节")
    void unexpectedReturns500WithoutDetails() {
        ResponseEntity<ApiResponse<Void>> r = handler.unexpected(new IllegalStateException("db password=secret"));
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
        assertThat(r.getBody()).isEqualTo(ApiResponse.error("INTERNAL_ERROR", "服务暂时不可用"));
        assertThat(r.getBody().message()).doesNotContain("secret");
    }
}
