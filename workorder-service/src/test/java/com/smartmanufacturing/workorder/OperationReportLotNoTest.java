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
        return new ReportOperationRequest(BigDecimal.ONE, BigDecimal.ZERO, BigDecimal.ZERO, "PCS", lotNo, null);
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

    @Test void blankLotNoIsRejected() {
        // 空串、纯空格、纯换行都视为空白
        assertThat(validator.validate(req(""))).isNotEmpty();
        assertThat(validator.validate(req("   "))).isNotEmpty();
        assertThat(validator.validate(req("\n"))).isNotEmpty();
    }

    @Test void lotNoLengthMatchesColumn() {
        assertThat(validator.validate(req("L".repeat(64)))).isEmpty();
        assertThat(validator.validate(req("L".repeat(65)))).isNotEmpty();
        assertThat(validator.validate(req(" LOT 1 "))).isEmpty();
    }
}
