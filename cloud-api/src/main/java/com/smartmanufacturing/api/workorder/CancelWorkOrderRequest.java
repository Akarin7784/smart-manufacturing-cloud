package com.smartmanufacturing.api.workorder;
import jakarta.validation.constraints.Size;
/** 取消工单请求（请求体可省略）：note 为取消说明，写入状态审计日志。 */
public record CancelWorkOrderRequest(@Size(max = 256) String note) {}
