package com.smartmanufacturing.inventory;
import org.junit.jupiter.api.Test;
import java.math.BigDecimal;
import static org.assertj.core.api.Assertions.*;

/** 数量解析与格式化：保护契约 Quantity 的取值范围（DECIMAL(18,6)）与输出规范化，防止浮点或科学计数法混入。 */
class QuantitiesTest {
    /** 边界值：最大 12 位整数 + 6 位小数、最小正数都必须原样解析。 */
    @Test void parsesBoundaryValues() {
        assertThat(Quantities.parsePositive("qty", "999999999999.999999")).isEqualByComparingTo("999999999999.999999");
        assertThat(Quantities.parsePositive("qty", "0.000001")).isEqualByComparingTo("0.000001");
    }

    /** 超出精度、负数、科学计数法、0 都按 validation-error 拒绝。 */
    @Test void rejectsOutOfContractValues() {
        for (String bad : new String[] {"1000000000000", "1.0000001", "-1", "1e3", "0", "0.000000", "", " 1"}) {
            assertThatThrownBy(() -> Quantities.parsePositive("qty", bad)).as(bad)
                .isInstanceOfSatisfying(ProblemException.class, e -> assertThat(e.type()).isEqualTo(ProblemType.VALIDATION_ERROR));
        }
        assertThatThrownBy(() -> Quantities.parsePositive("qty", null)).isInstanceOf(ProblemException.class);
    }

    /** DECIMAL(18,6) 读回固定 6 位小数，输出时去掉末尾 0；0 输出为 "0" 而不是 "0E-6"。 */
    @Test void formatsWithoutTrailingZeros() {
        assertThat(Quantities.format(new BigDecimal("10.500000"))).isEqualTo("10.5");
        assertThat(Quantities.format(new BigDecimal("100.000000"))).isEqualTo("100");
        assertThat(Quantities.format(new BigDecimal("0.000000"))).isEqualTo("0");
        assertThat(Quantities.format(new BigDecimal("-3.250000"))).isEqualTo("-3.25");
    }

    /** 每种错误类型的状态码固定，与契约 ProblemType 表一致。 */
    @Test void problemTypesMapToContractStatus() {
        assertThat(ProblemType.INSUFFICIENT_STOCK.status().value()).isEqualTo(409);
        assertThat(ProblemType.OVER_CONSUME.status().value()).isEqualTo(422);
        assertThat(ProblemType.IDEMPOTENCY_KEY_CONFLICT.status().value()).isEqualTo(422);
        assertThat(ProblemType.RESERVATION_EXPIRED.status().value()).isEqualTo(410);
        assertThat(ProblemType.RESERVATION_RELEASED.status().value()).isEqualTo(410);
        assertThat(ProblemType.RESERVATION_CONSUMED.status().value()).isEqualTo(409);
        assertThat(ProblemType.LINE_CLOSED.status().value()).isEqualTo(409);
        assertThat(ProblemType.VALIDATION_ERROR.status().value()).isEqualTo(400);
    }
}
