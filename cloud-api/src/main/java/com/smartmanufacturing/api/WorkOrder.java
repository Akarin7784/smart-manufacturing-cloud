package com.smartmanufacturing.api;
/** 工单响应契约，仅包含工单服务对外公开的数据字段。 */
public record WorkOrder(String id, String deviceId, String product, int quantity, String status) {}
