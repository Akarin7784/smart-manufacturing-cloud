package com.smartmanufacturing.workorder;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import java.time.Clock;
import java.time.ZoneId;

/**
 * 用途：工单服务的基础装配。
 * <ul>
 *   <li>业务时钟：下达时“当天生效”的 BOM / 工艺按工厂时区（manufacturing.workorder.zone，默认 Asia/Shanghai）计算，
 *       测试可替换为固定时钟；</li>
 *   <li>定时任务开关：manufacturing.workorder.scheduling-enabled 控制预留续约作业，集成测试中关闭以便手动驱动。</li>
 * </ul>
 */
@Configuration
@org.springframework.boot.context.properties.EnableConfigurationProperties(MqProperties.class)
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
