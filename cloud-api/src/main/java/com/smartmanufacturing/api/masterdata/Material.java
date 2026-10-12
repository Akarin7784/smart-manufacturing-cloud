package com.smartmanufacturing.api.masterdata;
/** 物料视图；version 为乐观锁版本，更新时必须回传。 */
public record Material(String code, String name, MaterialType type, String uom, TraceMode traceMode,
                       MaterialSource source, boolean active, long version) {}
