package com.smartmanufacturing.quality;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
/** 质检服务启动入口：扫描公共组件（请求 ID、Sentinel 降级等）与质检业务组件。 */
@SpringBootApplication(scanBasePackages = "com.smartmanufacturing")
public class QualityApplication {
    public static void main(String[] args) { SpringApplication.run(QualityApplication.class, args); }
}
