package com.smartmanufacturing.workorder;

import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;

import java.util.List;

/**
 * 质量服务查询契约：关闭工单前按工单号、状态统计 NCR。URL 为空时走服务发现；沿用 Feign 拦截器透传 Bearer 令牌，调用者需具备 quality:read。
 * 不配置降级：任何失败都由 {@link WorkOrderClosure} 翻译为 503 quality-service-unavailable，不伪造“没有 NCR”。
 */
@FeignClient(name = "quality-service", url = "${clients.quality.url:}")
public interface QualityClient {
    /** 质量服务 NCR 分页结果（与 quality-service NcrDtos.Page 字段一致；只用到 total）。 */
    record NcrPage(List<Object> items, int page, int size, long total) {}

    @GetMapping("/quality/ncrs")
    NcrPage ncrs(@RequestParam("workOrderNo") String workOrderNo, @RequestParam("status") String status,
                 @RequestParam("page") int page, @RequestParam("size") int size);
}
