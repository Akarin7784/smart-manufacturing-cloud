package com.smartmanufacturing.quality;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import java.time.Clock;
/** 系统时钟单独声明为 Bean，测试可注入可控时钟验证过期与保留期截止点。 */
@Configuration(proxyBeanMethods = false)
class ClockConfiguration {
    /** 使用 UTC 时钟：过期、流水时间均以 Instant 计算，与服务器时区无关。 */
    @Bean @ConditionalOnMissingBean
    Clock clock() { return Clock.systemUTC(); }
}
