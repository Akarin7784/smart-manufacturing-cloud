package com.smartmanufacturing.api.masterdata;
import jakarta.validation.constraints.*;
import java.math.BigDecimal;
/**
 * BOM 行：单位父件用量 qtyPer，损耗率 scrapRate（0.05 表示 5%）。
 * consumeOpNo 指定在父件工艺路线的哪道工序消耗，发布时校验其存在；phantom 表示虚拟件，展开时穿透到其子件。
 */
public record BomLine(@Min(1) @Max(9999) int lineNo,
                      @NotBlank @Size(max = 64) String componentCode,
                      @NotNull @DecimalMin(value = "0", inclusive = false) @Digits(integer = 8, fraction = 4) BigDecimal qtyPer,
                      @NotBlank @Size(max = 16) String uom,
                      @NotNull @DecimalMin("0") @DecimalMax("0.9999") @Digits(integer = 1, fraction = 4) BigDecimal scrapRate,
                      @NotNull IssueMode issueMode,
                      @Pattern(regexp = "OP[0-9]{2,4}") String consumeOpNo,
                      boolean phantom) {}
