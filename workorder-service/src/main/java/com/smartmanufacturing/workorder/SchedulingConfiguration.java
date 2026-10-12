package com.smartmanufacturing.workorder;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * 工单服务定时任务配置：只在启用幂等记录清理时开启调度，测试环境关闭后不会产生后台线程干扰用例。
 */
@Configuration(proxyBeanMethods = false)
@EnableScheduling
@ConditionalOnProperty(prefix = "workorder.idempotency.cleanup", name = "enabled", havingValue = "true", matchIfMissing = true)
class SchedulingConfiguration {
}
