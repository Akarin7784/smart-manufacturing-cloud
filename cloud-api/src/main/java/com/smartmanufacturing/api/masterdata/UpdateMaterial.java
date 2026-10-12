package com.smartmanufacturing.api.masterdata;
import jakarta.validation.constraints.*;
/** 更新物料可变属性；类型与来源影响 BOM 结构，不允许修改。expectedVersion 不一致时返回 409。 */
public record UpdateMaterial(@NotBlank @Size(max = 128) String name,
                             @NotBlank @Size(max = 16) String uom,
                             @NotNull TraceMode traceMode,
                             boolean active,
                             @PositiveOrZero long expectedVersion) {}
