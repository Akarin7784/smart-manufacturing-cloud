package com.smartmanufacturing.workorder;
import com.smartmanufacturing.api.ApiResponse;
import com.smartmanufacturing.api.masterdata.BomExplosion;
import com.smartmanufacturing.api.masterdata.Routing;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.*;
import java.math.BigDecimal;

/**
 * 主数据查询契约：只按业务编码读取已发布、在指定日期生效的 BOM 展开与工艺路线，不访问主数据库。
 * URL 为空时走服务发现；调用沿用 Feign 拦截器传播已验证的 Bearer 令牌，调用者需具备 masterdata:read。
 */
@FeignClient(name = "masterdata-service", url = "${clients.masterdata.url:}", fallbackFactory = MasterdataFallbackFactory.class)
public interface MasterdataClient {
    /** 指定日期、数量的多层 BOM 展开（仅已发布生效版本）。 */
    @GetMapping("/boms/{product}/explosion")
    ApiResponse<BomExplosion> explode(@PathVariable("product") String product, @RequestParam("date") String date,
                                      @RequestParam("quantity") BigDecimal quantity);
    /** 指定日期生效的已发布工艺路线。 */
    @GetMapping("/routings/{product}/effective")
    ApiResponse<Routing> effectiveRouting(@PathVariable("product") String product, @RequestParam("date") String date);
}
