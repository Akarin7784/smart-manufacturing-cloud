package com.smartmanufacturing.masterdata;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/** 主数据微服务启动入口，同时加载公共异常处理、请求关联与资源服务器安全组件。 */
@SpringBootApplication(scanBasePackages = "com.smartmanufacturing")
public class MasterdataApplication {
    public static void main(String[] args) { SpringApplication.run(MasterdataApplication.class, args); }
}
