package com.smartmanufacturing.api.masterdata;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** 修改工作中心请求（编码不可改）。 */
public record UpdateWorkCenter(@NotBlank @Size(max = 128) String name,
                               @NotBlank @Size(max = 64) String workshop,
                               boolean outsourced) {}
