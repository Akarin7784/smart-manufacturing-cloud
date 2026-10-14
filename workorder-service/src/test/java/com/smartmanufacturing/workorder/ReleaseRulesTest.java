package com.smartmanufacturing.workorder;
import com.smartmanufacturing.api.masterdata.*;
import com.smartmanufacturing.common.BusinessException;
import feign.FeignException;
import feign.Request;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import static org.assertj.core.api.Assertions.*;

/** 下达规则单元测试：需求汇总、消耗工序校验与主数据失败映射。 */
class ReleaseRulesTest {
    private static BomExplosionLine leaf(String code, String qty, String uom) {
        return new BomExplosionLine(1, "P/" + code, "P", code, BigDecimal.ONE, new BigDecimal(qty), uom, IssueMode.PICK, null, true, "A01");
    }

    /** 叶子件按物料汇总并按编码排序，非叶子件不预留。 */
    @Test void aggregatesLeafRequirements() {
        var lines = WorkOrderReleaseService.requirements(ReleaseFixtures.explosion(10).lines());
        assertThat(lines).extracting(l -> l.materialCode()).containsExactly("RM-STEEL", "STD-BOLT");
        assertThat(lines.get(1).quantity()).isEqualByComparingTo("60");
    }

    /** 同一物料单位不一致返回 422 uom-mismatch。 */
    @Test void rejectsMixedUom() {
        assertThatThrownBy(() -> WorkOrderReleaseService.requirements(List.of(leaf("A", "1", "KG"), leaf("A", "1", "G"))))
            .isInstanceOfSatisfying(BusinessException.class, e -> {
                assertThat(e.status()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
                assertThat(e.code()).isEqualTo(WorkOrderProblemType.UOM_MISMATCH.code());
            });
    }

    /** 全部为非叶子件时没有可预留的物料。 */
    @Test void rejectsNoLeaves() {
        var l = new BomExplosionLine(1, "P/A", "P", "A", BigDecimal.ONE, BigDecimal.ONE, "PCS", IssueMode.PICK, null, false, "A01");
        assertThatThrownBy(() -> WorkOrderReleaseService.requirements(List.of(l))).isInstanceOf(BusinessException.class);
    }

    /** BOM 行引用的消耗工序必须存在于工艺路线。 */
    @Test void validatesConsumeOperations() {
        var ops = ReleaseFixtures.routing().operations();
        assertThatCode(() -> WorkOrderReleaseService.validateConsumeOps(ReleaseFixtures.explosion(1).lines(), ops)).doesNotThrowAnyException();
        var bad = new BomExplosionLine(1, "P/A", "P", "A", BigDecimal.ONE, BigDecimal.ONE, "PCS", IssueMode.PICK, "OP99", true, "A01");
        assertThatThrownBy(() -> WorkOrderReleaseService.validateConsumeOps(List.of(bad), ops)).isInstanceOf(BusinessException.class);
        // 第 2 层引用组件自身路线的工序，不受成品路线约束
        var deep = new BomExplosionLine(2, "P/S/A", "S", "A", BigDecimal.ONE, BigDecimal.ONE, "PCS", IssueMode.PICK, "OP99", true, "A01");
        assertThatCode(() -> WorkOrderReleaseService.validateConsumeOps(List.of(deep), ops)).doesNotThrowAnyException();
    }

    /** 主数据失败映射：404→422，403 透传，其余 503。 */
    @Test void mapsMasterdataFailures() {
        Request req = Request.create(Request.HttpMethod.GET, "/x", new HashMap<>(), null, StandardCharsets.UTF_8, null);
        assertThat(MasterdataFallbackFactory.map(new FeignException.NotFound("", req, null, null)).status()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
        assertThat(MasterdataFallbackFactory.map(new FeignException.Forbidden("", req, null, null)).status()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(MasterdataFallbackFactory.map(new RuntimeException("timeout")).status()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
    }

    /** #71：主数据错误改为 problem+json 后，映射只看 HTTP 状态、不解析响应体（type / code），新旧响应体映射结果相同。 */
    @Test void mapsMasterdataProblemJsonByStatusOnly() {
        Request req = Request.create(Request.HttpMethod.GET, "/x", new HashMap<>(), null, StandardCharsets.UTF_8, null);
        var headers = java.util.Map.<String, java.util.Collection<String>>of("Content-Type", List.of("application/problem+json"));
        byte[] notEffective = "{\"type\":\"bom-not-effective\",\"title\":\"无生效 BOM\",\"status\":404,\"code\":\"BOM_NOT_EFFECTIVE\"}".getBytes(StandardCharsets.UTF_8);
        byte[] cycle = "{\"type\":\"bom-cycle\",\"status\":422,\"code\":\"BOM_CYCLE\"}".getBytes(StandardCharsets.UTF_8);
        byte[] internal = "{\"type\":\"internal-error\",\"status\":500}".getBytes(StandardCharsets.UTF_8);
        assertThat(MasterdataFallbackFactory.map(new FeignException.NotFound("", req, notEffective, headers)).code()).isEqualTo("RELEASE_MASTERDATA_INVALID");
        assertThat(MasterdataFallbackFactory.map(new FeignException.UnprocessableEntity("", req, cycle, headers)).code()).isEqualTo("RELEASE_MASTERDATA_INVALID");
        assertThat(MasterdataFallbackFactory.map(new FeignException.InternalServerError("", req, internal, headers)).code()).isEqualTo("MASTERDATA_SERVICE_UNAVAILABLE");
    }

    /** 本地预留替身：同一幂等键得到同一预留单号，到期时间为 now + 15 分钟。 */
    @Test void localReservationIsDeterministic() {
        var clock = java.time.Clock.fixed(java.time.Instant.parse("2026-10-12T00:00:00Z"), java.time.ZoneOffset.UTC);
        var port = new LocalMaterialReservation(clock);
        var a = port.reserve("k", "o", List.of());
        assertThat(port.reserve("k", "o", List.of()).reservationId()).isEqualTo(a.reservationId());
        assertThat(port.reserve("k2", "o", List.of()).reservationId()).isNotEqualTo(a.reservationId());
        assertThat(a.expiresAt()).isEqualTo(clock.instant().plusSeconds(900));
    }

    /** 数量规范为 scale 6（DECIMAL(18,6)）；超过 6 位小数拒绝而不截断。 */
    @Test void quantitiesAreScale6AndOverPrecisionRejected() {
        assertThat(WorkOrderReleaseService.requirements(ReleaseFixtures.explosion(10).lines())).allSatisfy(l -> assertThat(l.quantity().scale()).isEqualTo(6));
        assertThatThrownBy(() -> WorkOrderReleaseService.scale6(new BigDecimal("0.0000001")))
            .isInstanceOfSatisfying(BusinessException.class, e -> assertThat(e.code()).isEqualTo("RELEASE_MASTERDATA_INVALID"));
    }
}
