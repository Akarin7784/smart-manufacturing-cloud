package com.smartmanufacturing.workorder;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import java.time.Clock;
import java.time.ZoneId;

/** 工单服务基础 Bean：业务时钟（下达日期按工厂时区计算，测试可替换）与可开关的定时任务。 */
@Configuration
public class WorkOrderConfiguration {
    /** 工厂时区时钟；BOM / 工艺生效日期按此时区的“当天”判断。 */
    @Bean Clock workOrderClock(@Value("${manufacturing.workorder.zone:Asia/Shanghai}") String zone) {
        return Clock.system(ZoneId.of(zone));
    }

    /** 定时任务开关：集成测试关闭，以手动调用续约方法获得确定性结果。 */
    @Configuration
    @EnableScheduling
    @ConditionalOnProperty(name = "manufacturing.workorder.scheduling-enabled", havingValue = "true", matchIfMissing = true)
    static class Scheduling { }
}
