package com.smartmanufacturing.api.masterdata;
/** 工位：工作中心下的具体作业位置，设备实例由 device-service 绑定到工位编码。 */
public record Station(String code, String name, String workCenterCode, boolean deleted) {
    /** 兼容 PR2 之前的构造方式：未删除。 */
    public Station(String code, String name, String workCenterCode) { this(code, name, workCenterCode, false); }
}
