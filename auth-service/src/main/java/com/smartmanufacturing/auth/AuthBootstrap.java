package com.smartmanufacturing.auth;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.*;
import org.springframework.jdbc.core.JdbcTemplate;

/** 空认证数据库首次启动时创建管理员；密码由环境注入，没有固定默认密码。 */
@Configuration
public class AuthBootstrap {
    /** 已有用户时不重置密码，避免服务重启覆盖管理员设置。 */
    @Bean ApplicationRunner initializeAdmin(AuthUserService users, JdbcTemplate jdbc,
            @Value("${auth.bootstrap.username:admin}") String username,
            @Value("${auth.bootstrap.password:}") String password) {
        return args -> {
            if (jdbc.queryForObject("select count(*) from auth_user", Integer.class) > 0) return;
            if (!username.matches("[a-zA-Z0-9._-]{3,64}") || password.length() < 12 || password.length() > 72)
                throw new IllegalStateException("首次初始化认证库需提供有效 AUTH_BOOTSTRAP_PASSWORD；可运行 scripts/init-auth.ps1");
            users.create(new CreateUserRequest(username, password, Role.ADMIN));
        };
    }
}
