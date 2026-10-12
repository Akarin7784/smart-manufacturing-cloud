package com.smartmanufacturing.auth;
import jakarta.validation.constraints.NotNull;
/** 管理员更新用户角色与启用状态；变更影响后续登录签发的令牌。 */
public record UpdateUserRequest(@NotNull Role role, @NotNull Boolean enabled) {}
