package com.smartmanufacturing.inventory;

import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * 库存服务定时任务配置：开启调度。各任务自身按配置开关（幂等清理、过期与 outbox 发布），测试环境全部关闭定时触发。
 */
@Configuration(proxyBeanMethods = false)
@EnableScheduling
class SchedulingConfiguration {
}
