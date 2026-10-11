package com.smartmanufacturing.workorder;
import com.smartmanufacturing.api.*;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.*;
/** 设备调用契约；URL 为空时使用服务发现和负载均衡。 */
@FeignClient(name = "device-service", url = "${clients.device.url:}", fallbackFactory = DeviceFallbackFactory.class)
public interface DeviceClient {
    /** 查询指定设备，HTTP 错误由降级工厂转换为业务异常。 */
    @GetMapping("/devices/{id}") ApiResponse<Device> get(@PathVariable("id") String id);
}
