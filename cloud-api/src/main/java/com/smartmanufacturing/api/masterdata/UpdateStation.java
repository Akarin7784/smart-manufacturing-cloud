package com.smartmanufacturing.api.masterdata;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** 修改工位请求（编码与所属工作中心不可改）。 */
public record UpdateStation(@NotBlank @Size(max = 128) String name) {}
