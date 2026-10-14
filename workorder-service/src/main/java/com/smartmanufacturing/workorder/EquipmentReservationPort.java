package com.smartmanufacturing.workorder;

/**
 * 工序开工时的设备（工位）预留扩展点（设计 docs/workorder-dispatch-design.md §7）。
 * <p>M1 只固定调用时机：开工 / 恢复时 reserve，暂停 / 完工时 release；实现必须以 key 幂等。
 * P2 接入 device-service 后，预留失败将以 409 device-offline / 503 device-service-unavailable 拒绝开工，M1 不产生这两种错误。
 */
public interface EquipmentReservationPort {
    /** 为任务预留工位设备；key 形如 task-{工单号}-{工序号}，重复调用返回同一预留。 */
    void reserve(String workOrderNo, String operationNo, String stationCode, String key);

    /** 释放预留；不存在的预留静默成功。 */
    void release(String workOrderNo, String operationNo, String stationCode, String key);
}
