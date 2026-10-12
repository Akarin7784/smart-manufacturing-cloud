package com.smartmanufacturing.workorder;

import org.springframework.stereotype.Component;

/**
 * 默认空实现：device-service 尚未提供预留接口（设计文档 P2）。
 * 当前创建工单仍只做设备在线校验，查询与写入之间的竞态被显式接受并记录在 PR 中；
 * 接入真实预留时以 Feign 实现替换本类（删除本类或改为按 profile 装配），调用方无需改动。
 */
@Component
public class NoopDeviceReservation implements DeviceReservationPort {
    /** 不做占用，仅保留调用时机。 */
    @Override public void reserve(String reservationKey, String deviceId) { }
    /** 无预留可释放。 */
    @Override public void release(String reservationKey, String deviceId) { }
}
