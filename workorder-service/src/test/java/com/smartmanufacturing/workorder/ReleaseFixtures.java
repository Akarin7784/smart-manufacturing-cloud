package com.smartmanufacturing.workorder;
import com.smartmanufacturing.api.ApiResponse;
import com.smartmanufacturing.api.masterdata.*;
import org.springframework.jdbc.core.JdbcTemplate;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.when;

/** 下达测试公共夹具：GR-60 减速机的简化 BOM / 工艺主数据桩与建单、清表工具。 */
final class ReleaseFixtures {
    private ReleaseFixtures() {}
    static final String PRODUCT = "FG-GR60-20";

    /** 两层 BOM：外壳组件（自制，非叶子）下挂钢板，另有螺栓两处使用以验证按物料汇总。 */
    static BomExplosion explosion(int qty) {
        BigDecimal q = BigDecimal.valueOf(qty);
        return new BomExplosion(PRODUCT, "A01", q, LocalDate.now(), List.of(
            new BomExplosionLine(1, PRODUCT + "/SA-HOUSING", PRODUCT, "SA-HOUSING", BigDecimal.ONE, q, "PCS", IssueMode.PICK, "OP10", false, "A01"),
            new BomExplosionLine(2, PRODUCT + "/SA-HOUSING/RM-STEEL", "SA-HOUSING", "RM-STEEL", new BigDecimal("1.05"), new BigDecimal("1.05").multiply(q), "KG", IssueMode.PICK, null, true, "A01"),
            new BomExplosionLine(2, PRODUCT + "/SA-HOUSING/STD-BOLT", "SA-HOUSING", "STD-BOLT", new BigDecimal("4"), q.multiply(BigDecimal.valueOf(4)), "PCS", IssueMode.BACKFLUSH, null, true, "A01"),
            new BomExplosionLine(1, PRODUCT + "/STD-BOLT", PRODUCT, "STD-BOLT", new BigDecimal("2"), q.multiply(BigDecimal.valueOf(2)), "PCS", IssueMode.BACKFLUSH, "OP20", true, "A01")));
    }

    static Routing routing() {
        return new Routing(PRODUCT, "R01", LifecycleStatus.RELEASED, LocalDate.now().minusDays(1), null, List.of(
            new Operation("OP10", "壳体加工", "WC-CNC", 30, 120, true, true, List.of("IP-FAI-HOUSING")),
            new Operation("OP20", "总装", "WC-ASM", 10, 300, true, false, List.of())));
    }

    static void stubMasterdata(MasterdataClient client) {
        when(client.explode(eq(PRODUCT), anyString(), any())).thenAnswer(inv -> ApiResponse.ok(explosion(((BigDecimal) inv.getArgument(2)).intValue())));
        when(client.effectiveRouting(eq(PRODUCT), anyString())).thenReturn(ApiResponse.ok(routing()));
    }

    static String createOrder(JdbcTemplate jdbc, int qty) {
        String id = UUID.randomUUID().toString();
        jdbc.update("insert into work_order (id, device_id, product, quantity, status) values (?, 'D001', ?, ?, 'CREATED')", id, PRODUCT, qty);
        return id;
    }

    static void clear(JdbcTemplate jdbc) {
        for (String t : List.of("wo_operation_report", "wo_operation_progress", "wo_command_request", "work_order_lifecycle", "work_order_hold_reason", "wo_bom_snapshot", "operation_task", "wo_material_reservation",
            "wo_material_reservation_line", "work_order_status_log", "outbox_event", "consumed_event", "work_order_request", "work_order"))
            jdbc.update("delete from " + t);
    }
}
