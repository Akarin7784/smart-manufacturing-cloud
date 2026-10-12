package com.smartmanufacturing.common;
import org.springframework.http.HttpStatus;
/** 业务异常：业务码 + HTTP 状态；details 为可选的结构化明细（如缺料行），原样放入响应 data 返回调用方。 */
public class BusinessException extends RuntimeException {
    private final String code;
    private final HttpStatus status;
    private final Object details;
    public BusinessException(String code, String message, HttpStatus status) { this(code, message, status, null); }
    public BusinessException(String code, String message, HttpStatus status, Object details) {
        super(message); this.code = code; this.status = status; this.details = details;
    }
    public String code() { return code; }
    public HttpStatus status() { return status; }
    /** 结构化明细，可能为 null。 */
    public Object details() { return details; }
}
