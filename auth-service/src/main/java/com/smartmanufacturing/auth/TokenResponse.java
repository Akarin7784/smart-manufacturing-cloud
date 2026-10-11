package com.smartmanufacturing.auth;
import java.util.List;
/** 登录成功响应；仅返回短期访问令牌，不通过 Cookie 自动保存身份。 */
public record TokenResponse(String accessToken, String tokenType, long expiresIn,
                            String username, Role role, List<String> permissions) {}
