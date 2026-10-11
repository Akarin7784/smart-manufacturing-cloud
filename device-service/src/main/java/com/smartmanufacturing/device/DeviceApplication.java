package com.smartmanufacturing.device;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/** 设备微服务启动入口，同时加载公共异常处理和请求关联组件。 */
@SpringBootApplication(scanBasePackages = "com.smartmanufacturing")
public class DeviceApplication {
    public static void main(String[] args) { SpringApplication.run(DeviceApplication.class, args); }
}
