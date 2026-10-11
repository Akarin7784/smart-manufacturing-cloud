package com.smartmanufacturing.workorder;
import feign.RequestInterceptor;
import org.slf4j.MDC;
import org.springframework.context.annotation.*;
/** 配置服务间请求头，将当前请求关联标识传递给设备服务。 */
@Configuration
public class FeignConfiguration {
    /** 读取当前线程 MDC 中的关联标识并写入 Feign 请求头。 */
    @Bean RequestInterceptor requestIdInterceptor() {
        return template -> {
            String id = MDC.get("requestId");
            if (id != null) template.header("X-Request-Id", id);
        };
    }
}
