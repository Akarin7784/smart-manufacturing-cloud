package com.smartmanufacturing.auth;
import jakarta.validation.constraints.*;
/** 登录输入，仅接受用户名和密码，不接受角色、用户编号或权限声明。 */
public record LoginRequest(@NotBlank @Size(max = 64) String username,
                           @NotBlank @Size(max = 256) String password) {}
