package com.smartmanufacturing.api;
import jakarta.validation.constraints.*;
/** 创建工单的输入契约；在入口校验设备编号、产品名称及数量范围。 */
public record CreateWorkOrder(@NotBlank @Size(max = 64) String deviceId,
                              @NotBlank @Size(max = 128) String product,
                              @Min(1) @Max(1000000) int quantity) {}
