package com.smartmanufacturing.inventory;

import com.fasterxml.jackson.databind.JsonNode;
import java.math.BigDecimal;
import java.util.Iterator;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * 质检隔离接口（/inventory/qc-hold/**）的规范化请求。由 {@link #parse} 从原始 JSON 严格解析：
 * <ul>
 *   <li>只接受契约声明的字段，多余字段（如 locationCode）一律 400 validation-error——库位由服务端固定为 QC-HOLD；</li>
 *   <li>releases 按 reason 约束 dispositionId：REWORK/CONCESSION 必填，NCR_VOIDED 禁止携带；scraps 必填；receipts 不接受。</li>
 * </ul>
 * 不用 Jackson 全局 FAIL_ON_UNKNOWN_PROPERTIES：只对这三个内部接口收紧，不改变其他接口的容错行为。
 *
 * @param qtyText 已规范化的正数数量（最简十进制字符串，参与幂等摘要）；{@link #qty()} 返回其数值
 */
public record QcHoldRequest(String ncrNo, String materialCode, String uom, String lotNo, String qtyText, String reason, Long dispositionId) {
    /** 三种操作。 */
    public enum Kind { RECEIPT, RELEASE, SCRAP }

    private static final Set<String> BASE = Set.of("ncrNo", "materialCode", "uom", "lotNo", "qty");
    private static final Set<String> RELEASE_FIELDS = Set.of("ncrNo", "materialCode", "uom", "lotNo", "qty", "reason", "dispositionId");
    private static final Set<String> SCRAP_FIELDS = Set.of("ncrNo", "materialCode", "uom", "lotNo", "qty", "dispositionId");
    /** 放行原因，与契约 QcHoldReleaseRequest.reason 一致。 */
    static final Set<String> REASONS = Set.of("REWORK", "CONCESSION", "NCR_VOIDED");
    private static final Pattern NCR_NO = Pattern.compile("^NCR-\\d{8}-\\d{4,}$");

    /** 数量（正数）。 */
    public BigDecimal qty() { return new BigDecimal(qtyText); }

    /** 严格解析并规范化（去首尾空白、数量最简形式），任何不符都抛 400。 */
    public static QcHoldRequest parse(Kind kind, JsonNode body) {
        if (body == null || !body.isObject()) throw ProblemException.validation("请求体必须是 JSON 对象");
        Set<String> allowed = switch (kind) { case RECEIPT -> BASE; case RELEASE -> RELEASE_FIELDS; case SCRAP -> SCRAP_FIELDS; };
        for (Iterator<String> it = body.fieldNames(); it.hasNext(); ) {
            String f = it.next();
            if (!allowed.contains(f)) throw ProblemException.validation("不支持的字段：" + f + "（库位由服务端固定为 QC-HOLD）");
        }
        String ncrNo = text(body, "ncrNo", 32);
        if (!NCR_NO.matcher(ncrNo).matches()) throw ProblemException.validation("ncrNo 格式非法：" + ncrNo);
        String material = text(body, "materialCode", 64);
        String uom = text(body, "uom", 16);
        String lot = text(body, "lotNo", 64);
        String qty = Quantities.format(Quantities.parsePositive("qty", text(body, "qty", 32)));
        String reason = null;
        Long disposition = body.has("dispositionId") ? dispositionId(body.get("dispositionId")) : null;
        if (kind == Kind.RELEASE) {
            reason = text(body, "reason", 16);
            if (!REASONS.contains(reason)) throw ProblemException.validation("reason 取值非法：" + reason);
            // 与契约 if/then/else 一致：作废扣回不关联处置行，处置放行必须关联。
            if ("NCR_VOIDED".equals(reason) && disposition != null) throw ProblemException.validation("reason=NCR_VOIDED 时不得携带 dispositionId");
            if (!"NCR_VOIDED".equals(reason) && disposition == null) throw ProblemException.validation("reason=" + reason + " 时必须携带 dispositionId");
        }
        if (kind == Kind.SCRAP && disposition == null) throw ProblemException.validation("报废出库必须携带 dispositionId");
        return new QcHoldRequest(ncrNo, material, uom, lot, qty, reason, disposition);
    }

    /** 必填字符串字段：必须是非空白字符串且不超过长度上限。 */
    private static String text(JsonNode body, String field, int max) {
        JsonNode v = body.get(field);
        if (v == null || !v.isTextual() || v.asText().isBlank()) throw ProblemException.validation(field + " 必填且必须为非空字符串");
        String s = v.asText().strip();
        if (s.length() > max) throw ProblemException.validation(field + " 长度不能超过 " + max);
        return s;
    }

    /** 处置行 ID：正整数（JSON number）。 */
    private static Long dispositionId(JsonNode v) {
        if (v == null || !v.canConvertToLong() || !v.isIntegralNumber() || v.asLong() <= 0)
            throw ProblemException.validation("dispositionId 必须为正整数");
        return v.asLong();
    }
}
