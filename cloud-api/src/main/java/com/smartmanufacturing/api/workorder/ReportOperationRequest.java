package com.smartmanufacturing.api.workorder;
import jakarta.validation.constraints.*;
import java.math.BigDecimal;
/**
 * 工序报工请求。数量最多 6 位小数（DECIMAL(18,6)），可用 JSON 数字或十进制字符串传入；三者均不得为负且合计必须大于 0。
 * lotNo 为可选批次号（随不良品事件发给质检）；uom 必须与本工单已有报工的单位一致。
 */
public record ReportOperationRequest(
    @NotNull @DecimalMin("0") @Digits(integer = 12, fraction = 6) BigDecimal goodQty,
    @NotNull @DecimalMin("0") @Digits(integer = 12, fraction = 6) BigDecimal defectQty,
    @NotNull @DecimalMin("0") @Digits(integer = 12, fraction = 6) BigDecimal scrapQty,
    @NotBlank @Size(max = 16) String uom,
    @Size(max = 64) String lotNo,
    @Size(max = 256) String note) {}
