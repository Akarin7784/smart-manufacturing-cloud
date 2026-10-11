package com.smartmanufacturing.auth;
/** 用户管理响应，不暴露密码散列、登录失败计数或锁定信息。 */
public record UserView(String id, String username, Role role, boolean enabled) {}
