package com.smartmanufacturing.api.workorder;
import jakarta.validation.constraints.Size;
/** 关闭工单请求（请求体可省略）：note 为关闭说明，写入状态审计日志。 */
public record CloseWorkOrderRequest(@Size(max = 256) String note) {}
