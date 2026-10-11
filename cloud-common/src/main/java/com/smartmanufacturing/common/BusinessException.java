package com.smartmanufacturing.common;
import org.springframework.http.HttpStatus;
/** 可对外返回的业务异常，同时携带业务码和对应的 HTTP 状态。 */
public class BusinessException extends RuntimeException {
    private final String code;
    private final HttpStatus status;
    public BusinessException(String code, String message, HttpStatus status) {
        super(message); this.code = code; this.status = status;
    }
    public String code() { return code; }
    public HttpStatus status() { return status; }
}
