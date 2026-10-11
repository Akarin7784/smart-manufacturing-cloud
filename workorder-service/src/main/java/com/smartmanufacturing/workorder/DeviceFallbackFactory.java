package com.smartmanufacturing.workorder;
import com.smartmanufacturing.common.BusinessException;
import feign.FeignException;
import org.slf4j.*;
import org.springframework.cloud.openfeign.FallbackFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
/** 区分设备不存在与设备服务故障，避免降级伪造成功。 */
@Component
public class DeviceFallbackFactory implements FallbackFactory<DeviceClient> {
    private static final Logger LOG = LoggerFactory.getLogger(DeviceFallbackFactory.class);
    /** 保留真实失败语义，无法确认设备状态时拒绝创建工单。 */
    @Override public DeviceClient create(Throwable cause) {
        return id -> {
            if (cause instanceof FeignException.NotFound)
                throw new BusinessException("DEVICE_NOT_FOUND", "设备不存在", HttpStatus.NOT_FOUND);
            LOG.warn("Device service call failed: {}", cause.toString());
            throw new BusinessException("DEVICE_SERVICE_UNAVAILABLE", "设备服务暂时不可用，工单未创建", HttpStatus.SERVICE_UNAVAILABLE);
        };
    }
}
