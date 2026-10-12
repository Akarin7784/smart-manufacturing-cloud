package com.smartmanufacturing.api.masterdata;
import jakarta.validation.constraints.*;
/** 新建工作中心请求。 */
public record CreateWorkCenter(@NotBlank @Pattern(regexp = "WC-[A-Z0-9-]{1,61}") String code,
                               @NotBlank @Size(max = 128) String name,
                               @NotBlank @Size(max = 64) String workshop,
                               boolean outsourced) {}
