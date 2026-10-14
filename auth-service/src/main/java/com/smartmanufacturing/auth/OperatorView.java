package com.smartmanufacturing.auth;

/**
 * 操作工下拉项（GET /auth/operators）：只含 id、工号、姓名，刻意不返回角色、启用状态、手机号等字段。
 * id 即令牌 sub，派工请求的 operator 取该值；employeeNo / name 为空的历史账号回退为用户名。
 */
public record OperatorView(String id, String employeeNo, String name) {}
