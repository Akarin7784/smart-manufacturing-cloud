package com.smartmanufacturing.masterdata;
import com.smartmanufacturing.api.masterdata.*;
import com.smartmanufacturing.common.BusinessException;
import org.junit.jupiter.api.Test;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

/** 多层展开算法的纯单元测试：不启动 Spring，保护数量计算、虚拟件穿透与循环检测规则。 */
class BomExploderTest {
    private static BomLine line(int no, String code, String qty, String scrap, boolean phantom) {
        return new BomLine(no, code, new BigDecimal(qty), "件", new BigDecimal(scrap), IssueMode.PICK, null, phantom);
    }
    private static Bom bom(String product, BomLine... lines) {
        return new Bom(product, "A01", LifecycleStatus.RELEASED, LocalDate.of(2026, 10, 1), null, List.of(lines));
    }

    /** 需求量逐层累乘单位用量与 (1+损耗率)，并标记叶子。 */
    @Test void multipliesQuantitiesAndScrapAcrossLevels() {
        Map<String, Bom> boms = Map.of("SA", bom("SA", line(10, "PT", "2", "0", false)),
                                       "PT", bom("PT", line(10, "RM", "0.5", "0.05", false)));
        var lines = new BomExploder(c -> Optional.ofNullable(boms.get(c)), 20)
            .explode(bom("FG", line(10, "SA", "1", "0", false)), new BigDecimal("10"));
        assertEquals(List.of("SA", "PT", "RM"), lines.stream().map(BomExplosionLine::componentCode).toList());
        assertEquals(List.of(1, 2, 3), lines.stream().map(BomExplosionLine::level).toList());
        // 10 × 1 × 2 × 0.5 × 1.05 = 10.5
        assertEquals(new BigDecimal("10.5000"), lines.get(2).requiredQty());
        assertEquals("FG/SA/PT/RM", lines.get(2).path());
        assertTrue(lines.get(2).leaf());
        assertFalse(lines.get(0).leaf());
    }

    /** 虚拟件本身不输出，其子件提升到虚拟件所在层级，父件记为上级产品。 */
    @Test void phantomIsTransparent() {
        Map<String, Bom> boms = Map.of("PH", bom("PH", line(10, "X", "3", "0", false)));
        var lines = new BomExploder(c -> Optional.ofNullable(boms.get(c)), 20)
            .explode(bom("FG", line(10, "PH", "2", "0", true)), BigDecimal.ONE);
        assertEquals(1, lines.size());
        assertEquals("X", lines.get(0).componentCode());
        assertEquals("FG", lines.get(0).parentCode());
        assertEquals(1, lines.get(0).level());
        assertEquals(new BigDecimal("6.0000"), lines.get(0).requiredQty());
    }

    /** 跨层循环引用（FG→A→B→A）必须报 BOM_CYCLE，而不是栈溢出。 */
    @Test void detectsIndirectCycle() {
        Map<String, Bom> boms = new HashMap<>();
        boms.put("A", bom("A", line(10, "B", "1", "0", false)));
        boms.put("B", bom("B", line(10, "A", "1", "0", false)));
        BusinessException e = assertThrows(BusinessException.class, () -> new BomExploder(c -> Optional.ofNullable(boms.get(c)), 20)
            .explode(bom("FG", line(10, "A", "1", "0", false)), BigDecimal.ONE));
        assertEquals("BOM_CYCLE", e.code());
    }

    /** 同一组件出现在不同分支（如两处使用同一轴承）不是循环，应各自展开。 */
    @Test void sharedComponentOnSiblingBranchesIsNotCycle() {
        Map<String, Bom> boms = Map.of("A", bom("A", line(10, "BRG", "2", "0", false)),
                                       "B", bom("B", line(10, "BRG", "2", "0", false)));
        var lines = new BomExploder(c -> Optional.ofNullable(boms.get(c)), 20)
            .explode(bom("FG", line(10, "A", "1", "0", false), line(20, "B", "1", "0", false)), BigDecimal.ONE);
        assertEquals(2, lines.stream().filter(l -> l.componentCode().equals("BRG")).count());
    }

    /** 超过最大深度时报错，防止异常长链。 */
    @Test void enforcesMaxDepth() {
        Map<String, Bom> boms = Map.of("L1", bom("L1", line(10, "L2", "1", "0", false)),
                                       "L2", bom("L2", line(10, "L3", "1", "0", false)));
        BusinessException e = assertThrows(BusinessException.class, () -> new BomExploder(c -> Optional.ofNullable(boms.get(c)), 2)
            .explode(bom("FG", line(10, "L1", "1", "0", false)), BigDecimal.ONE));
        assertEquals("BOM_TOO_DEEP", e.code());
    }
}
