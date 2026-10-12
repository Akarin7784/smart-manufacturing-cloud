package com.smartmanufacturing.workorder;
import com.smartmanufacturing.common.BusinessException;
import feign.FeignException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cloud.openfeign.FallbackFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

/**
 * 用途：MasterdataClient 的 Feign / Sentinel 降级工厂，把主数据调用失败翻译为下达接口的业务错误。
 * 保留真实失败语义，任何情况下都不伪造主数据（降级时工单一律不下达）。
 * 404/422（无生效版本、循环 BOM 等）→ 422 RELEASE_MASTERDATA_INVALID，工单不能下达；
 * 401/403 原样映射；超时、5xx、熔断 → 503，提示稍后重试。
 */
@Component
public class MasterdataFallbackFactory implements FallbackFactory<MasterdataClient> {
    private static final Logger LOG = LoggerFactory.getLogger(MasterdataFallbackFactory.class);
    @Override public MasterdataClient create(Throwable cause) {
        BusinessException mapped = map(cause);
        return new MasterdataClient() {
            @Override public com.smartmanufacturing.api.ApiResponse<com.smartmanufacturing.api.masterdata.BomExplosion> explode(String p, String d, java.math.BigDecimal q) { throw mapped; }
            @Override public com.smartmanufacturing.api.ApiResponse<com.smartmanufacturing.api.masterdata.Routing> effectiveRouting(String p, String d) { throw mapped; }
        };
    }
    static BusinessException map(Throwable cause) {
        if (cause instanceof FeignException.NotFound || cause instanceof FeignException.UnprocessableEntity)
            return new BusinessException("RELEASE_MASTERDATA_INVALID", "产品没有可用于下达的已发布 BOM / 工艺路线：" + cause.getMessage(), HttpStatus.UNPROCESSABLE_ENTITY);
        if (cause instanceof FeignException.Unauthorized)
            return new BusinessException("AUTHENTICATION_REQUIRED", "主数据服务拒绝当前令牌", HttpStatus.UNAUTHORIZED);
        if (cause instanceof FeignException.Forbidden)
            return new BusinessException("ACCESS_DENIED", "没有读取主数据的权限", HttpStatus.FORBIDDEN);
        LOG.warn("主数据服务调用失败，按 503 返回，工单不下达", cause);
        return new BusinessException("MASTERDATA_SERVICE_UNAVAILABLE", "主数据服务暂时不可用，工单未下达", HttpStatus.SERVICE_UNAVAILABLE);
    }
}
