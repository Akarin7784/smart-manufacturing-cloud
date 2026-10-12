package com.smartmanufacturing.events;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
/** 事件消费微服务入口，使用独立数据库记录处理结果。 */
@SpringBootApplication(scanBasePackages = "com.smartmanufacturing")
public class EventApplication {
    /** 启动事件消费与受保护的查询接口。 */
    public static void main(String[] args) { SpringApplication.run(EventApplication.class, args); }
}
