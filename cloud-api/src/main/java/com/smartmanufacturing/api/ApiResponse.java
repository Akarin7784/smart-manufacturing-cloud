package com.smartmanufacturing.api;
/** 统一响应结构：业务码描述结果，提示说明原因，数据字段承载接口返回值。 */
public record ApiResponse<T>(String code, String message, T data) {
    /** 构造成功响应，保留实际返回数据。 */
    public static <T> ApiResponse<T> ok(T data) { return new ApiResponse<>("OK", "success", data); }
    /** 构造错误响应，由业务码和 HTTP 状态共同描述失败。 */
    public static ApiResponse<Void> error(String code, String message) { return new ApiResponse<>(code, message, null); }
}
