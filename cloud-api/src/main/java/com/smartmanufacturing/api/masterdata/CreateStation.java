package com.smartmanufacturing.api.masterdata;
import jakarta.validation.constraints.*;
/** 在工作中心下新增工位的请求。 */
public record CreateStation(@NotBlank @Pattern(regexp = "ST-[A-Z0-9-]{1,61}") String code,
                            @NotBlank @Size(max = 128) String name) {}
