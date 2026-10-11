package com.smartmanufacturing.auth;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
/** 认证服务启动入口，负责用户身份验证及 JWT 签发。 */
@SpringBootApplication(scanBasePackages = "com.smartmanufacturing")
public class AuthApplication {
    /** 启动认证服务并加载独立的数据库和密钥配置。 */
    public static void main(String[] args) { SpringApplication.run(AuthApplication.class, args); }
}
