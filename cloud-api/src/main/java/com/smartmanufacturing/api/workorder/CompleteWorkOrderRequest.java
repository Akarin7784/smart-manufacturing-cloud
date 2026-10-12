package com.smartmanufacturing.api.workorder;
import jakarta.validation.constraints.*;
/** 手工完工请求：原因必填，写入审计日志与 WorkOrderCompleted 事件。 */
public record CompleteWorkOrderRequest(@NotBlank @Size(max = 256) String reason) {}
