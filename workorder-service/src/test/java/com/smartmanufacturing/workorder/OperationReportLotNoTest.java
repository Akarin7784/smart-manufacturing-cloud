package com.smartmanufacturing.workorder;

import com.smartmanufacturing.api.workorder.ReportOperationRequest;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;

/** 报工批次号规则单元测试：缺省取工单号；传入值不得为空白且不超过 64。 */
class OperationReportLotNoTest {

    private final Validator validator = Validation.buildDefaultValidatorFactory().getValidator();

    private ReportOperationRequest req(String lotNo) {
        return new ReportOperationRequest(BigDecimal.ONE, BigDecimal.ZERO, BigDecimal.ZERO, "PCS", lotNo, null, "1.00");
    }

    @Test void absentLotNoDefaultsToWorkOrderNo() {
        assertThat(OperationReportingService.effectiveLotNo(null, "WO-001")).isEqualTo("WO-001");
    }

    @Test void explicitLotNoIsKept() {
        assertThat(OperationReportingService.effectiveLotNo("LOT-9", "WO-001")).isEqualTo("LOT-9");
    }

    @Test void nullLotNoIsValid() {
        assertThat(validator.validate(req(null))).isEmpty();
    }

    @Test void lotNoIsTrimmedAtEntry() {
        assertThat(req(" LOT 1 ").lotNo()).isEqualTo("LOT 1");
        assertThat(req("\tLOT-2\n").lotNo()).isEqualTo("LOT-2");
        assertThat(OperationReportingService.effectiveLotNo(req(" LOT 1 ").lotNo(), "WO-001")).isEqualTo("LOT 1");
    }

    @Test void blankLotNoFallsBackToWorkOrderNo() {
        // 空串、纯空格、纯换行 trim 后为空，视同未传，缺省为工单号
        for (String blank : new String[] {"", "   ", "\n"}) {
            ReportOperationRequest r = req(blank);
            assertThat(r.lotNo()).isNull();
            assertThat(validator.validate(r)).isEmpty();
            assertThat(OperationReportingService.effectiveLotNo(r.lotNo(), "WO-001")).isEqualTo("WO-001");
        }
    }

    @Test void lotNoLengthIsCheckedAfterTrim() {
        assertThat(validator.validate(req("L".repeat(64)))).isEmpty();
        assertThat(validator.validate(req("L".repeat(65)))).isNotEmpty();
        // 首尾空白不计入长度
        assertThat(validator.validate(req("  " + "L".repeat(64) + "  "))).isEmpty();
        assertThat(validator.validate(req(" LOT 1 "))).isEmpty();
    }
}
