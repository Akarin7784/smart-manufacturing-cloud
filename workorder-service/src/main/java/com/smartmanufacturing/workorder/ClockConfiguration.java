package com.smartmanufacturing.workorder;

import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;

/** 系统时钟单独声明为 Bean，便于测试注入固定时钟验证保留期截止点。 */
@Configuration(proxyBeanMethods = false)
class ClockConfiguration {
    /** 使用 UTC 时钟：截止点以 Instant 计算，与时区无关。 */
    @Bean @ConditionalOnMissingBean
    Clock clock() { return Clock.systemUTC(); }
}
