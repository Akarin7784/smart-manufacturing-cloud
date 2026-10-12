package com.smartmanufacturing.api.masterdata;
import jakarta.validation.constraints.*;
import java.util.List;
/**
 * 工序：opNo 形如 OP10，同一路线内唯一并决定顺序；reportPoint 表示是否需要报工；
 * firstArticleRequired 为首件检门禁；inspectionPointCodes 引用质量上下文的检验点编码（不跨库校验）。
 */
public record Operation(@NotBlank @Pattern(regexp = "OP[0-9]{2,4}") String opNo,
                        @NotBlank @Size(max = 128) String name,
                        @NotBlank @Size(max = 64) String workCenterCode,
                        @PositiveOrZero @Max(100000) int setupMinutes,
                        @PositiveOrZero @Max(1000000) int runSecondsPerUnit,
                        boolean reportPoint,
                        boolean firstArticleRequired,
                        @NotNull @Size(max = 10) List<@Pattern(regexp = "IP-[A-Z0-9-]{1,61}") String> inspectionPointCodes) {}
