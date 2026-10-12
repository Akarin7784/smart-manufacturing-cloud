package com.smartmanufacturing.api.masterdata;
import jakarta.validation.constraints.*;
/** 新建物料请求；物料编码创建后不可修改，作为跨服务引用的业务键。 */
public record CreateMaterial(@NotBlank @Pattern(regexp = "[A-Z0-9][A-Z0-9-]{1,63}") String code,
                             @NotBlank @Size(max = 128) String name,
                             @NotNull MaterialType type,
                             @NotBlank @Size(max = 16) String uom,
                             @NotNull TraceMode traceMode,
                             @NotNull MaterialSource source) {}
