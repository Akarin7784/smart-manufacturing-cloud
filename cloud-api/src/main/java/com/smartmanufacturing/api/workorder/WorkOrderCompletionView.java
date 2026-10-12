package com.smartmanufacturing.api.workorder;
/** 手工完工结果：shortfall = 计划数 − 末道累计合格数（6 位小数字符串，可为 0）。 */
public record WorkOrderCompletionView(WorkOrderProgressView progress, String shortfall, String reason) {}
