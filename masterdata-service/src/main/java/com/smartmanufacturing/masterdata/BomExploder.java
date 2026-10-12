package com.smartmanufacturing.masterdata;
import com.smartmanufacturing.api.masterdata.*;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.*;
import java.util.function.Function;

/**
 * 多层 BOM 展开算法（纯函数，不访问数据库，便于单元测试）。
 * <ul>
 *   <li>需求量 = 父件需求量 × 单位用量 × (1 + 损耗率)，逐层累乘，保留 4 位小数（四舍五入）。</li>
 *   <li>子件存在生效 BOM 则继续展开，否则视为叶子（采购件或尚未建 BOM 的自制件）。</li>
 *   <li>虚拟件（phantom）不输出自身，直接把其子件提升到当前层级。</li>
 *   <li>沿当前路径检测循环引用，并限制最大深度，防止脏数据导致无限递归。</li>
 * </ul>
 */
final class BomExploder {
    static final int SCALE = 4;
    private final Function<String, Optional<Bom>> effectiveBom;
    private final int maxDepth;

    /** @param effectiveBom 按物料编码返回其在展开日期生效的 BOM */
    BomExploder(Function<String, Optional<Bom>> effectiveBom, int maxDepth) {
        this.effectiveBom = effectiveBom; this.maxDepth = maxDepth;
    }

    /** 展开根 BOM；根 BOM 由调用方给出，使发布前校验可以对“尚未发布的草稿”执行同一算法。 */
    List<BomExplosionLine> explode(Bom root, BigDecimal quantity) {
        List<BomExplosionLine> out = new ArrayList<>();
        Deque<String> path = new ArrayDeque<>();
        path.addLast(root.productCode());
        expand(root, quantity, 1, path, out);
        return out;
    }

    private void expand(Bom bom, BigDecimal parentQty, int level, Deque<String> path, List<BomExplosionLine> out) {
        if (level > maxDepth) throw Errors.rule("BOM_TOO_DEEP", "BOM 层级超过上限 " + maxDepth + ": " + String.join("/", path));
        for (BomLine line : bom.lines()) {
            String child = line.componentCode();
            if (path.contains(child))
                throw Errors.rule("BOM_CYCLE", "BOM 存在循环引用: " + String.join("/", path) + "/" + child);
            BigDecimal required = parentQty.multiply(line.qtyPer()).multiply(BigDecimal.ONE.add(line.scrapRate()))
                .setScale(SCALE, RoundingMode.HALF_UP);
            Optional<Bom> childBom = effectiveBom.apply(child);
            path.addLast(child);
            try {
                if (line.phantom() && childBom.isPresent()) {
                    // 虚拟件穿透：子件层级与虚拟件相同，父件编码仍记为当前 BOM 的产品。
                    expandPhantom(bom.productCode(), childBom.get(), required, level, path, out);
                    continue;
                }
                out.add(new BomExplosionLine(level, String.join("/", path), bom.productCode(), child, line.qtyPer(), required,
                    line.uom(), line.issueMode(), line.consumeOpNo(), childBom.isEmpty(), bom.version()));
                if (childBom.isPresent()) expand(childBom.get(), required, level + 1, path, out);
            } finally {
                path.removeLast();
            }
        }
    }

    /** 以虚拟件的 BOM 为来源展开到同一层级；子件行保留各自的消耗工序与发料方式。 */
    private void expandPhantom(String parent, Bom phantomBom, BigDecimal qty, int level, Deque<String> path, List<BomExplosionLine> out) {
        Bom lifted = new Bom(parent, phantomBom.version(), phantomBom.status(), phantomBom.effectiveFrom(), phantomBom.effectiveTo(), phantomBom.lines());
        expand(lifted, qty, level, path, out);
    }
}
