package com.smartmanufacturing.inventory;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
/** 库存服务启动入口：扫描公共组件（请求 ID、Sentinel 降级等）与库存业务组件。 */
@SpringBootApplication(scanBasePackages = "com.smartmanufacturing")
public class InventoryApplication {
    public static void main(String[] args) { SpringApplication.run(InventoryApplication.class, args); }
}
