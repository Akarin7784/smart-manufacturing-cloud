package com.smartmanufacturing.masterdata;
import com.smartmanufacturing.api.masterdata.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import javax.sql.DataSource;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

/**
 * GR-60 演示主数据测试：使用独立的 H2 库叠加 demo profile，验证脚本可加载、可重复执行，
 * 且直接写入 RELEASED 的 BOM 全部满足服务端发布规则（演示数据不得绕过业务约束）。
 */
@SpringBootTest
@ActiveProfiles({"test", "demo"})
@TestPropertySource(properties = "spring.datasource.url=jdbc:h2:mem:masterdata-demo;MODE=MySQL;DB_CLOSE_DELAY=-1")
class DemoDataTest {
    private static final LocalDate DEMO_DATE = LocalDate.of(2026, 10, 12);
    @Autowired JdbcTemplate jdbc;
    @Autowired DataSource dataSource;
    @Autowired BomService boms;
    @Autowired RoutingService routings;
    @MockitoBean org.springframework.security.oauth2.jwt.JwtDecoder decoder;

    private Map<String, Integer> counts() {
        Map<String, Integer> c = new LinkedHashMap<>();
        for (String t : List.of("material", "work_center", "station", "bom_header", "bom_line", "routing_header", "routing_operation"))
            c.put(t, jdbc.queryForObject("select count(*) from " + t, Integer.class));
        return c;
    }

    /** 数量与设计文档一致：27 种物料、7 个工作中心、12 个产品的 BOM 与工艺；重复执行脚本后数量不变（幂等）。 */
    @Test void demoScriptIsCompleteAndIdempotent() {
        Map<String, Integer> first = counts();
        assertEquals(27, first.get("material"));
        assertEquals(7, first.get("work_center"));
        assertEquals(12, first.get("bom_header"));
        assertEquals(12, first.get("routing_header"));
        new ResourceDatabasePopulator(new ClassPathResource("db/demo/R__demo_data.sql")).execute(dataSource);
        assertEquals(first, counts());
    }

    /** 每个演示 BOM 都通过发布校验：组件存在且启用、无循环、消耗工序存在于同产品生效工艺路线。 */
    @Test void everyDemoBomSatisfiesReleaseRules() {
        for (String product : jdbc.queryForList("select product_code from bom_header", String.class))
            assertDoesNotThrow(() -> boms.validateForRelease(boms.effective(product, DEMO_DATE)), product);
    }

    /** 成品四层展开：10 台需求下 20CrMnTi 圆钢 = 10×0.45×1.05 + 10×0.55×1.05 = 10.5 kg，螺栓含 1% 损耗 = 60.6。 */
    @Test void explodesGearReducer() {
        BomExplosion e = boms.explode("FG-GR60-20", DEMO_DATE, BigDecimal.TEN);
        assertEquals("A01", e.bomVersion());
        // 成品为第 0 层，组件 1 层、零件 2 层、原材料 3 层，共四层结构。
        assertEquals(3, e.lines().stream().mapToInt(BomExplosionLine::level).max().orElseThrow());
        BigDecimal steel = e.lines().stream().filter(l -> l.componentCode().equals("RM-20CRMNTI-D30"))
            .map(BomExplosionLine::requiredQty).reduce(BigDecimal.ZERO, BigDecimal::add);
        assertEquals(0, new BigDecimal("10.5").compareTo(steel));
        BomExplosionLine bolts = e.lines().stream().filter(l -> l.componentCode().equals("PP-BOLT-M6")).findFirst().orElseThrow();
        assertEquals(0, new BigDecimal("60.6").compareTo(bolts.requiredQty()));
        assertEquals("OP50", bolts.consumeOpNo());
        // 轴承 6202 出现在输入轴与中间轴两个分支，每台各 2 个，10 台合计 40。
        assertEquals(0, new BigDecimal("40").compareTo(e.lines().stream().filter(l -> l.componentCode().equals("PP-BRG-6202"))
            .map(BomExplosionLine::requiredQty).reduce(BigDecimal.ZERO, BigDecimal::add)));
    }

    /** 成品工艺 8 道工序，跑合测试工序挂接两个检验点；输出大齿轮含委外热处理与磨齿。 */
    @Test void routingsMatchDesign() {
        Routing fg = routings.effective("FG-GR60-20", DEMO_DATE);
        assertEquals(8, fg.operations().size());
        assertEquals(List.of("IP-FQC-RUN", "IP-FQC-LEAK"), fg.operations().get(6).inspectionPointCodes());
        Routing g4 = routings.effective("PT-GR60-G4", DEMO_DATE);
        assertEquals(List.of("OP10", "OP20", "OP30", "OP40", "OP50", "OP60"), g4.operations().stream().map(Operation::opNo).toList());
        assertEquals("WC-HT", g4.operations().get(2).workCenterCode());
    }
}
