package com.smartmanufacturing.auth;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.*;
import org.springframework.core.Ordered;
import org.springframework.jdbc.core.JdbcTemplate;

/** 空认证数据库首次启动时创建管理员；密码由环境注入，没有固定默认密码。 */
@Configuration
public class AuthBootstrap {
    /** 已有用户时不重置密码，避免服务重启覆盖管理员设置。 */
    @Bean @org.springframework.core.annotation.Order(Ordered.HIGHEST_PRECEDENCE)
    ApplicationRunner initializeAdmin(AuthUserService users, JdbcTemplate jdbc,
            @Value("${auth.bootstrap.username:admin}") String username,
            @Value("${auth.bootstrap.password:}") String password) {
        return args -> {
            if (jdbc.queryForObject("select count(*) from auth_user", Integer.class) > 0) return;
            if (!username.matches("[a-zA-Z0-9._-]{3,64}") || password.length() < 12 || password.length() > 72)
                throw new IllegalStateException("首次初始化认证库需提供有效 AUTH_BOOTSTRAP_PASSWORD；可运行 scripts/init-auth.ps1");
            users.create(new CreateUserRequest(username, password, Role.ADMIN));
        };
    }

    /**
     * 演示账号：qc01（质检员）与 op01（操作员，用于演示和冒烟中验证只读质检权限）。
     * 密码只来自环境变量（init-env 随机生成并写入被 Git 忽略的 .env），未配置时跳过，不存在默认密码。
     * 按用户名幂等：账号已存在时不重置密码和角色，管理员后续的修改不会被重启覆盖。
     * 排在管理员初始化之后执行，保证"空库才创建管理员"的判断不受演示账号影响。
     */
    @Bean @org.springframework.core.annotation.Order(Ordered.LOWEST_PRECEDENCE)
    ApplicationRunner initializeDemoUsers(AuthUserService users, JdbcTemplate jdbc,
            @Value("${auth.demo-users.qc01-password:}") String qcPassword,
            @Value("${auth.demo-users.op01-password:}") String opPassword) {
        return args -> {
            seed(users, jdbc, "qc01", qcPassword, Role.QUALITY_INSPECTOR);
            seed(users, jdbc, "op01", opPassword, Role.OPERATOR);
        };
    }

    private static void seed(AuthUserService users, JdbcTemplate jdbc, String username, String password, Role role) {
        if (password.isEmpty()) return;
        if (password.length() < 12 || password.length() > 72)
            throw new IllegalStateException("演示账号 " + username + " 的密码长度须为 12–72 个字符；可运行 scripts/init-env.sh 重新生成");
        if (jdbc.queryForObject("select count(*) from auth_user where username = ?", Integer.class, username) > 0) return;
        users.create(new CreateUserRequest(username, password, role));
    }
}
