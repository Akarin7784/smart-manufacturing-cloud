package com.smartmanufacturing.api.workorder;

import java.util.List;

/** 主数据引用检查结果：count 为仍在执行中的引用工单数，workOrderNos 最多列出前 10 个工单号。 */
public record WorkOrderReferenceView(int count, List<String> workOrderNos) {}
