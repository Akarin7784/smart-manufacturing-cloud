package com.smartmanufacturing.api.workorder;
import jakarta.validation.constraints.*;
/** 冲销请求：整条冲销（不支持部分冲销），原因必填并写入冲销记录。 */
public record ReverseReportRequest(@NotBlank @Size(max = 256) String reason) {}
