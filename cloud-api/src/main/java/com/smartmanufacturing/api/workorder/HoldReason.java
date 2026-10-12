package com.smartmanufacturing.api.workorder;
/**
 * 工单冻结原因（设计文档 3.1）：可多原因并存，全部解除后才恢复。
 * MATERIAL_RESERVATION_EXPIRED 由库存预留过期事件自动产生，解除时会重新预留物料。
 */
public enum HoldReason { MANUAL, LOT_HELD, FAI_FAILED, EQUIPMENT_FAULT, MATERIAL_RESERVATION_EXPIRED }
