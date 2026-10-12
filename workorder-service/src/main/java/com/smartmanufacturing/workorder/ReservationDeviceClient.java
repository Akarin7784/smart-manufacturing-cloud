package com.smartmanufacturing.workorder;
import com.smartmanufacturing.api.*;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.*;
/** 专用事务分支客户端；调用失败必须抛出异常，禁止降级返回虚假成功。 */
@FeignClient(name="device-service",contextId="reservationDeviceClient",url="${clients.device.url:}")
public interface ReservationDeviceClient {
    /** 预留设备，Bearer 和 XID 由公共 Feign 拦截器传递。 */
    @PostMapping("/devices/reservations") ApiResponse<Reservation> reserve(@RequestBody CreateReservation request);
    /** 释放设备，作为全局释放事务的远程分支。 */
    @DeleteMapping("/devices/reservations/{id}") ApiResponse<String> release(@PathVariable("id")String id);
}
