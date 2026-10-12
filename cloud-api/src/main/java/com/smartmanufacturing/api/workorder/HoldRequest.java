package com.smartmanufacturing.api.workorder;
import jakarta.validation.constraints.*;
/** 冻结请求；note 为可选说明，写入审计日志。 */
public record HoldRequest(@NotNull HoldReason reason, @Size(max = 256) String note) {}
