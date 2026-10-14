package com.smartmanufacturing.auth;
import java.util.List;
/** 系统角色及固定权限映射；客户端不能在登录请求中自行声明角色。 */
public enum Role {
    // 库存：操作员负责收发料，授予读写；查看者只读。
    // 主数据维护与发布暂只授予 ADMIN；工艺员（ENGINEER）等新角色按设计文档 2.4 在后续 PR 引入。
    // 质检：ADMIN 与质检员（QUALITY_INSPECTOR）可读写（评审、处置、关闭 NCR）；其余业务角色只读，便于现场查看不良品状态。
    ADMIN(List.of("device:read", "workorder:read", "workorder:write", "config:read", "metrics:read", "user:manage",
        "masterdata:read", "masterdata:write", "masterdata:release", "inventory:read", "inventory:write",
        "quality:read", "quality:write", "execution:dispatch", "execution:report")),
    // M1：操作工新增 execution:report（开工 / 暂停 / 恢复 / 报工）；workorder:write 兼容期保留，P2 收紧。
    OPERATOR(List.of("device:read", "workorder:read", "workorder:write", "masterdata:read", "inventory:read", "inventory:write",
        "quality:read", "execution:report")),
    VIEWER(List.of("device:read", "workorder:read", "masterdata:read", "inventory:read", "quality:read")),
    // 质检员：负责 NCR 评审与不良品处置；可查看工单、主数据和库存以核对不良来源，但不能改工单或出入库。
    QUALITY_INSPECTOR(List.of("device:read", "workorder:read", "masterdata:read", "inventory:read", "quality:read", "quality:write")),
    // 计划员（M1 新增，PM 2026-10-14 确认）：派工 / 改派 / 取消派工与操作工下拉；可查看工单与主数据（校验工位），
    // 不含 workorder:write，不能下达、冻结、报工或修改工单。
    PLANNER(List.of("device:read", "workorder:read", "masterdata:read", "quality:read", "execution:dispatch"));
    private final List<String> permissions;
    Role(List<String> permissions) { this.permissions = permissions; }
    /** 获取角色允许的接口操作，用于构造服务端签名声明。 */
    public List<String> permissions() { return permissions; }
}
