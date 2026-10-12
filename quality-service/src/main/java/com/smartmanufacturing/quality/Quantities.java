package com.smartmanufacturing.quality;
import java.math.BigDecimal;
import java.util.regex.Pattern;

/**
 * 数量的解析与格式化。对外以十进制字符串传输（避免 JSON 浮点误差），对内统一为 BigDecimal，落库为 DECIMAL(18,6)。
 * 取值范围与契约 Quantity 一致：整数部分最多 12 位、小数最多 6 位、不允许负号与科学计数法。
 */
public final class Quantities {
    /** 与 OpenAPI 中 Quantity.pattern 保持一致；修改时两处同步。 */
    public static final String PATTERN = "^\\d{1,12}(\\.\\d{1,6})?$";
    private static final Pattern COMPILED = Pattern.compile(PATTERN);
    private Quantities() {}

    /**
     * 解析请求中的数量并要求大于 0。
     * @throws ProblemException 格式非法或不大于 0 时抛出 validation-error（400）
     */
    public static BigDecimal parsePositive(String field, String raw) {
        if (raw == null || !COMPILED.matcher(raw).matches())
            throw ProblemException.validation(field + " 必须是最多 12 位整数、6 位小数的非负十进制字符串");
        BigDecimal value = new BigDecimal(raw);
        if (value.signum() <= 0) throw ProblemException.validation(field + " 必须大于 0");
        return value;
    }

    /** 规范化输出：去掉末尾 0（DECIMAL(18,6) 读回固定 6 位小数），使 "10" 与 "10.000000" 表示一致；0 输出为 "0"。 */
    public static String format(BigDecimal value) {
        if (value.signum() == 0) return "0";
        return value.stripTrailingZeros().toPlainString();
    }
}
