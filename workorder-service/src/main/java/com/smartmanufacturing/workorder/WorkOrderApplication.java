package com.smartmanufacturing.workorder;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cloud.openfeign.EnableFeignClients;
/** 工单服务启动入口，启用 OpenFeign 客户端并加载公共服务组件。 */
@EnableFeignClients
@SpringBootApplication(scanBasePackages = "com.smartmanufacturing")
public class WorkOrderApplication {
    public static void main(String[] args) { SpringApplication.run(WorkOrderApplication.class, args); }
}
