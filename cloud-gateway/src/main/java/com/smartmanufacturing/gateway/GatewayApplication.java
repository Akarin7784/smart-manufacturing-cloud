package com.smartmanufacturing.gateway;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/** 响应式网关启动入口，加载路由、服务发现和网关治理组件。 */
@SpringBootApplication(scanBasePackages = "com.smartmanufacturing")
public class GatewayApplication {
    public static void main(String[] args) { SpringApplication.run(GatewayApplication.class, args); }
}
