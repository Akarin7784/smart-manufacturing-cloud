package com.smartmanufacturing.auth;
import java.util.List;
/** 系统角色及固定权限映射；客户端不能在登录请求中自行声明角色。 */
public enum Role {
    ADMIN(List.of("device:read", "workorder:read", "workorder:write", "config:read", "metrics:read", "user:manage")),
    OPERATOR(List.of("device:read", "workorder:read", "workorder:write")),
    VIEWER(List.of("device:read", "workorder:read"));
    private final List<String> permissions;
    Role(List<String> permissions) { this.permissions = permissions; }
    /** 获取角色允许的接口操作，用于构造服务端签名声明。 */
    public List<String> permissions() { return permissions; }
}
