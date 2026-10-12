package com.smartmanufacturing.workorder;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * 默认空实现：device-service 尚未提供预留接口（设计文档 P2）。
 * 当前创建工单仍只做设备在线校验，查询与写入之间的竞态被显式接受并记录在 PR 中；
 * 按属性装配：manufacturing.workorder.reservation=noop（缺省）时启用；接入真实实现时将其设为 feign，
 * 并给 Feign 实现标注 havingValue = "feign"，保证任一时刻只有一个 {@link DeviceReservationPort} Bean。
 * 不使用 @ConditionalOnMissingBean：它在组件扫描的用户配置中依赖注册顺序，结果不可靠。
 */
@Component
@ConditionalOnProperty(name = "manufacturing.workorder.reservation", havingValue = "noop", matchIfMissing = true)
public class NoopDeviceReservation implements DeviceReservationPort {
    /** 不做占用，仅保留调用时机。 */
    @Override public void reserve(String reservationKey, String deviceId) { }
    /** 无预留可释放。 */
    @Override public void release(String reservationKey, String deviceId) { }
}
