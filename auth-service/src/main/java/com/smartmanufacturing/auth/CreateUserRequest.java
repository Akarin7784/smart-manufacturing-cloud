package com.smartmanufacturing.auth;
import jakarta.validation.constraints.*;
/** 管理员创建用户的输入，用户名限定安全字符，密码至少十二个字符。 */
public record CreateUserRequest(@NotBlank @Pattern(regexp = "[a-zA-Z0-9._-]{3,64}") String username,
                                @NotBlank @Size(min = 12, max = 72) String password,
                                @NotNull Role role) {}
