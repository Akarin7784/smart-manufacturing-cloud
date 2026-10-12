package com.smartmanufacturing.workorder;

/**
 * 设备资源预留扩展点。
 * <p>
 * 设计文档 3.3 约定：读取设备状态不等于占用设备，后续由 device-service 提供
 * “预留 → 确认 / 释放 + TTL”接口。本接口固定调用时机，便于在不引入分布式事务的前提下接入：
 * 先在远程预留，再写本地事务；本地事务失败时调用 {@link #release} 补偿，补偿遗漏由预留 TTL 兜底。
 */
public interface DeviceReservationPort {
    /**
     * 为工单预留设备；实现必须以 {@code reservationKey} 幂等，重复调用返回同一预留。
     * 无法确认预留时应抛出业务异常，调用方不会写入工单。
     */
    void reserve(String reservationKey, String deviceId);

    /** 释放（补偿）预留；实现必须幂等，对不存在或已过期的预留静默成功。 */
    void release(String reservationKey, String deviceId);
}
