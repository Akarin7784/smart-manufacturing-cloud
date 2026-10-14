package com.smartmanufacturing.masterdata;

import com.smartmanufacturing.api.ApiResponse;
import com.smartmanufacturing.api.masterdata.*;
import com.smartmanufacturing.api.workorder.WorkOrderReferenceView;
import com.smartmanufacturing.common.BusinessException;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * M1 PR2 主数据软删除 / 作废在真实 MySQL 8.4 上的 SQL 语义（需要 Docker，只在 CI 运行）：V2 迁移、
 * 引用查询（concat 在 MySQL 中是字符串拼接，|| 在默认 sql_mode 下是逻辑或）、作废后 current_flag 生成列置空并允许发布新版本。
 */
@Testcontainers
@SpringBootTest
@ActiveProfiles("test")
class MasterdataSoftDeleteMySqlIT {
    @Container @ServiceConnection
    static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.4");

    @Autowired WorkCenterService workCenters;
    @Autowired MaterialService materials;
    @Autowired RoutingService routings;
    @Autowired BomService boms;
    @Autowired JdbcTemplate jdbc;
    @MockitoBean WorkOrderReferenceClient workOrders;
    @MockitoBean org.springframework.security.oauth2.jwt.JwtDecoder decoder;

    /** V2 迁移已执行。 */
    @Test void migrationV2Applied() {
        assertThat(jdbc.queryForObject("select count(*) from flyway_schema_history where version = '2' and success = 1", Integer.class)).isEqualTo(1);
    }

    /** 引用明细格式为 产品/版本；作废后不再计入，软删除生效；BOM 引用阻止物料停用。 */
    @Test void referencesAndRetireOnMySql() {
        when(workOrders.references(any(), any())).thenReturn(ApiResponse.ok(new WorkOrderReferenceView(0, List.of())));
        workCenters.create(new CreateWorkCenter("WC-MY1", "中心", "W1", false));
        workCenters.addStation("WC-MY1", new CreateStation("ST-MY1-01", "工位"));
        materials.create(new CreateMaterial("MY-FG", "成品", MaterialType.FG, "件", TraceMode.LOT, MaterialSource.MAKE));
        materials.create(new CreateMaterial("MY-RM", "原料", MaterialType.RM, "件", TraceMode.LOT, MaterialSource.BUY));
        routings.create(new SaveRouting("MY-FG", "R01", LocalDate.of(2026, 10, 1), List.of(new Operation("OP10", "加工", "WC-MY1", 0, 60, true, false, List.of()))));
        routings.release("MY-FG", "R01");
        boms.create(new SaveBom("MY-FG", "A01", LocalDate.of(2026, 10, 1),
            List.of(new BomLine(10, "MY-RM", BigDecimal.ONE, "件", BigDecimal.ZERO, IssueMode.PICK, null, false))));

        BusinessException routing = catchThrowableOfType(BusinessException.class, () -> workCenters.delete("WC-MY1"));
        assertThat(routing.code()).isEqualTo("MASTERDATA_IN_USE");
        assertThat(routing.details().toString()).contains("MY-FG/R01");
        BusinessException bom = catchThrowableOfType(BusinessException.class,
            () -> materials.update("MY-RM", new UpdateMaterial("原料", "件", TraceMode.LOT, false, 0)));
        assertThat(bom.details().toString()).contains("MY-FG/A01").contains("BOM");

        routings.retire("MY-FG", "R01");
        assertThat(jdbc.queryForObject("select current_flag from routing_header where product_code = 'MY-FG' and version = 'R01'", Integer.class)).isNull();
        workCenters.delete("WC-MY1");
        assertThat(workCenters.list(false)).noneMatch(w -> w.code().equals("WC-MY1"));
        assertThat(workCenters.list(true)).filteredOn(w -> w.code().equals("WC-MY1")).singleElement()
            .satisfies(w -> assertThat(w.deleted()).isTrue());

        boms.deleteDraft("MY-FG", "A01");
        assertThat(materials.update("MY-RM", new UpdateMaterial("原料", "件", TraceMode.LOT, false, 0)).active()).isFalse();
    }
}
