package com.smartmanufacturing.masterdata;
import com.smartmanufacturing.api.masterdata.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.*;

/**
 * BOM 应用服务：草稿维护、发布（含跨聚合校验）、生效版本查询与多层展开。
 * 发布后版本只读；变更必须新建版本，从而保证已下达工单的 BOM 快照可复现。
 */
@Service
public class BomService {
    private final JdbcTemplate jdbc;
    private final VersionedHeaderDao headers;
    private final MaterialRepository materials;
    private final RoutingService routings;
    private final int maxDepth;

    BomService(JdbcTemplate jdbc, MaterialRepository materials, RoutingService routings,
               @Value("${manufacturing.masterdata.explosion-max-depth:20}") int maxDepth) {
        this.jdbc = jdbc; this.headers = new VersionedHeaderDao(jdbc, "bom_header");
        this.materials = materials; this.routings = routings; this.maxDepth = maxDepth;
    }

    @Transactional(readOnly = true)
    public Bom get(String product, String version) {
        VersionedHeaderDao.Header h = headers.find(product, version)
            .orElseThrow(() -> Errors.notFound("BOM_NOT_FOUND", "BOM 不存在: " + product + "/" + version));
        return toBom(h);
    }
    @Transactional(readOnly = true)
    public List<Bom> listByProduct(String product) { return headers.listByProduct(product).stream().map(this::toBom).toList(); }

    /** 指定日期生效的 BOM；无生效版本返回 404。 */
    @Transactional(readOnly = true)
    public Bom effective(String product, LocalDate date) {
        return findEffective(product, date).orElseThrow(() -> Errors.notFound("BOM_NOT_EFFECTIVE", "产品在 " + date + " 无生效 BOM: " + product));
    }

    /** 多层展开：工单下达时以此结果作为 BOM 快照。 */
    @Transactional(readOnly = true)
    public BomExplosion explode(String product, LocalDate date, BigDecimal quantity) {
        Bom root = effective(product, date);
        List<BomExplosionLine> lines = new BomExploder(code -> findEffective(code, date), maxDepth).explode(root, quantity);
        return new BomExplosion(product, root.version(), quantity, date, lines);
    }

    /** 新建草稿；产品必须是自制物料，版本号重复返回 409。 */
    @Transactional
    public Bom create(SaveBom request) {
        Material product = materials.find(request.productCode())
            .orElseThrow(() -> Errors.notFound("MATERIAL_NOT_FOUND", "物料不存在: " + request.productCode()));
        if (product.source() != MaterialSource.MAKE) throw Errors.rule("BOM_PRODUCT_NOT_MAKE", "采购件不能建立 BOM: " + product.code());
        validateLines(request);
        try {
            headers.insertDraft(request.productCode(), request.version(), request.effectiveFrom());
        } catch (DuplicateKeyException e) {
            throw Errors.conflict("BOM_VERSION_EXISTS", "BOM 版本已存在: " + request.productCode() + "/" + request.version());
        }
        insertLines(request);
        return get(request.productCode(), request.version());
    }

    /** 整体替换草稿的生效日和行；非草稿返回 409。条件更新放在最前，防止与发布并发时改动已发布版本。 */
    @Transactional
    public Bom replaceDraft(String product, String version, SaveBom request) {
        if (!product.equals(request.productCode()) || !version.equals(request.version()))
            throw Errors.rule("BOM_KEY_MISMATCH", "路径与请求体中的产品/版本不一致");
        get(product, version);
        validateLines(request);
        if (headers.updateDraftFrom(product, version, request.effectiveFrom()) == 0)
            throw Errors.conflict("BOM_NOT_DRAFT", "只有草稿 BOM 可修改: " + product + "/" + version);
        jdbc.update("delete from bom_line where product_code = ? and version = ?", product, version);
        insertLines(request);
        return get(product, version);
    }

    /**
     * 发布草稿。校验：组件物料全部存在且启用；不得自引用或形成循环；consumeOpNo 必须存在于
     * 产品在本 BOM 生效日生效的工艺路线中；生效日须晚于当前最新已发布版本。通过后截断上一版本的生效区间。
     */
    @Transactional
    public Bom release(String product, String version) {
        // 并发控制：同一产品不同草稿并发发布时，“读取最新已发布版本 → 校验生效日 → 发布 → 截断上一版本”
        // 必须串行，否则两个事务都以同一旧版本为基准，产生两个 effective_to 为空的 RELEASED 版本。
        // 以产品物料行作为锁对象（FOR UPDATE），BOM 与工艺路线各自的发布也因此互斥，锁粒度为单个产品。
        if (!materials.lockForUpdate(product)) throw Errors.notFound("BOM_NOT_FOUND", "BOM不存在: " + product + "/" + version);
        Bom draft = get(product, version);
        if (draft.status() != LifecycleStatus.DRAFT) throw Errors.conflict("BOM_NOT_DRAFT", "只有草稿 BOM 可发布: " + product + "/" + version);
        validateForRelease(draft);
        Optional<VersionedHeaderDao.Header> previous = headers.latestReleased(product);
        if (previous.isPresent() && !draft.effectiveFrom().isAfter(previous.get().effectiveFrom()))
            throw Errors.conflict("EFFECTIVE_DATE_CONFLICT", "生效日必须晚于当前已发布版本 " + previous.get().version() + " 的生效日 " + previous.get().effectiveFrom());
        // 先截断上一版本再发布本版本：两步之间不会出现两个开放区间，满足 uk_*_current 唯一约束。
        previous.ifPresent(p -> headers.truncate(product, p.version(), draft.effectiveFrom()));
        try {
            if (headers.release(product, version) == 0) throw Errors.conflict("BOM_NOT_DRAFT", "BOM 已被并发发布: " + product + "/" + version);
        } catch (org.springframework.dao.DuplicateKeyException e) {
            // 行锁之外的兜底（如绕过服务直接写库）：唯一约束拒绝第二个当前版本，整笔事务回滚。
            throw Errors.conflict("BOM_RELEASE_CONFLICT", "该产品已存在其他当前生效的已发布版本: " + product);
        }
        return get(product, version);
    }

    /** 发布校验；包内可见，供演示数据一致性测试复用同一规则。 */
    void validateForRelease(Bom bom) {
        Set<String> codes = new TreeSet<>();
        bom.lines().forEach(l -> codes.add(l.componentCode()));
        for (String code : codes) {
            Material m = materials.find(code).orElseThrow(() -> Errors.rule("BOM_COMPONENT_NOT_FOUND", "组件物料不存在: " + code));
            if (!m.active()) throw Errors.rule("BOM_COMPONENT_INACTIVE", "组件物料已停用: " + code);
        }
        // 以草稿替代产品自身的生效 BOM 进行一次展开，即可发现跨层循环引用。
        new BomExploder(code -> code.equals(bom.productCode()) ? Optional.of(bom) : findEffective(code, bom.effectiveFrom()), maxDepth)
            .explode(bom, BigDecimal.ONE);
        boolean needsRouting = bom.lines().stream().anyMatch(l -> l.consumeOpNo() != null);
        if (needsRouting) {
            Set<String> ops = routings.effectiveOpNos(bom.productCode(), bom.effectiveFrom())
                .orElseThrow(() -> Errors.rule("BOM_ROUTING_MISSING", "BOM 指定了消耗工序，但产品在 " + bom.effectiveFrom() + " 无生效工艺路线"));
            for (BomLine l : bom.lines())
                if (l.consumeOpNo() != null && !ops.contains(l.consumeOpNo()))
                    throw Errors.rule("BOM_OPERATION_NOT_FOUND", "行 " + l.lineNo() + " 的消耗工序不在工艺路线中: " + l.consumeOpNo());
        }
    }

    /** 草稿阶段即可判定的结构规则：行号唯一、不得直接自引用。 */
    private static void validateLines(SaveBom r) {
        Set<Integer> lineNos = new HashSet<>();
        for (BomLine l : r.lines()) {
            if (!lineNos.add(l.lineNo())) throw Errors.rule("BOM_LINE_DUPLICATE", "BOM 行号重复: " + l.lineNo());
            if (l.componentCode().equals(r.productCode())) throw Errors.rule("BOM_CYCLE", "BOM 不能引用产品自身");
        }
    }
    private void insertLines(SaveBom r) {
        // 草稿阶段仅依赖外键保证组件存在，发布时再做启用状态等完整校验。
        try {
            for (BomLine l : r.lines())
                jdbc.update("insert into bom_line (product_code, version, line_no, component_code, qty_per, uom, scrap_rate, issue_mode, consume_op_no, phantom) values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                    r.productCode(), r.version(), l.lineNo(), l.componentCode(), l.qtyPer(), l.uom(), l.scrapRate(), l.issueMode().name(), l.consumeOpNo(), l.phantom());
        } catch (org.springframework.dao.DataIntegrityViolationException e) {
            throw Errors.rule("BOM_COMPONENT_NOT_FOUND", "BOM 行引用了不存在的物料");
        }
    }
    private Optional<Bom> findEffective(String product, LocalDate date) { return headers.effective(product, date).map(this::toBom); }
    private Bom toBom(VersionedHeaderDao.Header h) {
        List<BomLine> lines = jdbc.query("select line_no, component_code, qty_per, uom, scrap_rate, issue_mode, consume_op_no, phantom from bom_line where product_code = ? and version = ? order by line_no",
            (rs, n) -> new BomLine(rs.getInt(1), rs.getString(2), rs.getBigDecimal(3).setScale(BomExploder.SCALE), rs.getString(4),
                rs.getBigDecimal(5).setScale(BomExploder.SCALE), IssueMode.valueOf(rs.getString(6)), rs.getString(7), rs.getBoolean(8)),
            h.productCode(), h.version());
        return new Bom(h.productCode(), h.version(), h.status(), h.effectiveFrom(), h.effectiveTo(), lines);
    }
}
