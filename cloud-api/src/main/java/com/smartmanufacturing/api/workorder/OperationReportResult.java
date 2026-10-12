package com.smartmanufacturing.api.workorder;
/** 报工 / 冲销结果：本次写入的流水与写入后的工单进度。 */
public record OperationReportResult(OperationReportView report, WorkOrderProgressView progress) {}
