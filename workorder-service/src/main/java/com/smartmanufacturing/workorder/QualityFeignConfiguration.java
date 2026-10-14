package com.smartmanufacturing.workorder;

import feign.RequestInterceptor;
import org.springframework.context.annotation.Bean;

/**
 * {@link QualityClient} 专用 Feign 配置（不加 @Configuration，避免被组件扫描成全局配置）：
 * 只在该客户端的子上下文注册服务令牌拦截器。全局的用户令牌透传拦截器对 quality-service 跳过，见 {@link FeignConfiguration}。
 */
class QualityFeignConfiguration {
    @Bean RequestInterceptor qualityServiceTokenInterceptor(QualityServiceToken token) { return token::apply; }
}
