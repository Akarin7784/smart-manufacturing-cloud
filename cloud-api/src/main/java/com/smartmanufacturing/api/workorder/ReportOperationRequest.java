package com.smartmanufacturing.api.workorder;
import jakarta.validation.constraints.*;
import java.math.BigDecimal;
/**
 * 工序报工请求。数量最多 6 位小数（DECIMAL(18,6)），可用 JSON 数字或十进制字符串传入；三者均不得为负且合计必须大于 0。
 * lotNo 为可选批次号：入口处先去掉首尾空白，去空白后为空（含纯空格）视同未传、缺省为工单号；
 * laborHours 为本次报工总工时（小时，字符串，≥0，最多 2 位小数），冲销时一并反冲。
 * 去空白后最长 64（与 wo_operation_report.lot_no 一致）。生效值
 * 落库并随不良品事件发给质检，供质检回写库存透传；uom 必须与本工单已有报工的单位一致。
 */
public record ReportOperationRequest(
    @NotNull @DecimalMin("0") @Digits(integer = 12, fraction = 6) BigDecimal goodQty,
    @NotNull @DecimalMin("0") @Digits(integer = 12, fraction = 6) BigDecimal defectQty,
    @NotNull @DecimalMin("0") @Digits(integer = 12, fraction = 6) BigDecimal scrapQty,
    @NotBlank @Size(max = 16) String uom,
    // 可选；构造时 trim，空白归一为 null（缺省工单号），长度按 trim 后校验
    @Size(max = 64) String lotNo,
    @Size(max = 256) String note,
    // M1 新增必填：本次报工总工时（小时），十进制字符串，非负、最多 2 位小数；负数、缺失、3 位小数均返回 validation-error
    @NotNull @Pattern(regexp = "\\d{1,6}(\\.\\d{1,2})?", message = "laborHours 必须是非负、最多 2 位小数的十进制字符串") String laborHours) {
    public ReportOperationRequest {
        if (lotNo != null) {
            lotNo = lotNo.strip();
            if (lotNo.isEmpty()) lotNo = null;
        }
    }
}
