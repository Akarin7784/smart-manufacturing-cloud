package com.smartmanufacturing.auth;
import java.util.List;
/** 系统角色及固定权限映射；客户端不能在登录请求中自行声明角色。 */
public enum Role {
    // 主数据维护与发布暂只授予 ADMIN；工艺员（ENGINEER）等新角色按设计文档 2.4 在后续 PR 引入。
    ADMIN(List.of("device:read", "workorder:read", "workorder:write", "config:read", "metrics:read", "user:manage",
        "masterdata:read", "masterdata:write", "masterdata:release")),
    OPERATOR(List.of("device:read", "workorder:read", "workorder:write", "masterdata:read")),
    VIEWER(List.of("device:read", "workorder:read", "masterdata:read"));
    private final List<String> permissions;
    Role(List<String> permissions) { this.permissions = permissions; }
    /** 获取角色允许的接口操作，用于构造服务端签名声明。 */
    public List<String> permissions() { return permissions; }
}
