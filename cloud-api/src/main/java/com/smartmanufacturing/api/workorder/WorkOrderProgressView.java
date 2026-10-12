package com.smartmanufacturing.api.workorder;
import java.util.List;
/** 工单报工进度：状态、计划数与各工序累计。 */
public record WorkOrderProgressView(String workOrderNo, String status, String plannedQty, List<OperationProgressView> operations) {}
