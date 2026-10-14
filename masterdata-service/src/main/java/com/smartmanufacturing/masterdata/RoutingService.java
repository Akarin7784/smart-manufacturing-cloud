package com.smartmanufacturing.masterdata;
import com.smartmanufacturing.api.masterdata.*;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.time.LocalDate;
import java.util.*;

/** 工艺路线应用服务：草稿维护、发布、生效版本查询。生命周期规则与 BOM 相同，共用 {@link VersionedHeaderDao}。 */
@Service
public class RoutingService {
    private final JdbcTemplate jdbc;
    private final VersionedHeaderDao headers;
    private final MaterialRepository materials;
    private final WorkCenterService workCenters;

    RoutingService(JdbcTemplate jdbc, MaterialRepository materials, WorkCenterService workCenters) {
        this.jdbc = jdbc; this.headers = new VersionedHeaderDao(jdbc, "routing_header");
        this.materials = materials; this.workCenters = workCenters;
    }

    @Transactional(readOnly = true)
    public Routing get(String product, String version) {
        return toRouting(headers.find(product, version)
            .orElseThrow(() -> Errors.notFound("ROUTING_NOT_FOUND", "工艺路线不存在: " + product + "/" + version)));
    }
    @Transactional(readOnly = true)
    public List<Routing> listByProduct(String product) { return headers.listByProduct(product).stream().map(this::toRouting).toList(); }
    @Transactional(readOnly = true)
    public Routing effective(String product, LocalDate date) {
        return headers.effective(product, date).map(this::toRouting)
            .orElseThrow(() -> Errors.notFound("ROUTING_NOT_EFFECTIVE", "产品在 " + date + " 无生效工艺路线: " + product));
    }
    /** 供 BOM 发布校验使用：返回指定日期生效路线的工序号集合。 */
    Optional<Set<String>> effectiveOpNos(String product, LocalDate date) {
        return headers.effective(product, date).map(h -> new HashSet<>(jdbc.queryForList(
            "select op_no from routing_operation where product_code = ? and version = ?", String.class, h.productCode(), h.version())));
    }

    @Transactional
    public Routing create(SaveRouting r) {
        Material product = materials.find(r.productCode())
            .orElseThrow(() -> Errors.notFound("MATERIAL_NOT_FOUND", "物料不存在: " + r.productCode()));
        if (product.source() != MaterialSource.MAKE) throw Errors.rule("ROUTING_PRODUCT_NOT_MAKE", "采购件不能建立工艺路线: " + product.code());
        validateOperations(r);
        try {
            headers.insertDraft(r.productCode(), r.version(), r.effectiveFrom());
        } catch (DuplicateKeyException e) {
            throw Errors.conflict("ROUTING_VERSION_EXISTS", "工艺路线版本已存在: " + r.productCode() + "/" + r.version());
        }
        insertOperations(r);
        return get(r.productCode(), r.version());
    }

    @Transactional
    public Routing replaceDraft(String product, String version, SaveRouting r) {
        if (!product.equals(r.productCode()) || !version.equals(r.version()))
            throw Errors.rule("ROUTING_KEY_MISMATCH", "路径与请求体中的产品/版本不一致");
        get(product, version);
        validateOperations(r);
        if (headers.updateDraftFrom(product, version, r.effectiveFrom()) == 0)
            throw Errors.conflict("ROUTING_NOT_DRAFT", "只有草稿工艺路线可修改: " + product + "/" + version);
        jdbc.update("delete from routing_operation where product_code = ? and version = ?", product, version);
        insertOperations(r);
        return get(product, version);
    }

    /**
     * 作废已发布版本（PR2）：RELEASED → OBSOLETE，按产品行锁与发布串行。不恢复上一版本的生效区间（被截断的旧版本保持截断），
     * 作废当前版本后该产品在作废日之后没有生效工艺路线，需要发布新版本。已下达工单使用的是下达时的快照，不受影响。
     */
    @Transactional
    public Routing retire(String product, String version) {
        if (!materials.lockForUpdate(product)) throw Errors.notFound("ROUTING_NOT_FOUND", "工艺路线不存在: " + product + "/" + version);
        get(product, version);
        if (headers.retire(product, version) == 0)
            throw Errors.conflict("ROUTING_NOT_RELEASED", "只有已发布的工艺路线可以作废: " + product + "/" + version);
        return get(product, version);
    }

    /** 删除草稿（PR2）：先删明细再删版本头；已发布 / 作废版本 409 ROUTING_NOT_DRAFT。 */
    @Transactional
    public void deleteDraft(String product, String version) {
        Routing current = get(product, version);
        if (current.status() != LifecycleStatus.DRAFT)
            throw Errors.conflict("ROUTING_NOT_DRAFT", "只有草稿工艺路线可以删除: " + product + "/" + version);
        jdbc.update("delete from routing_operation where product_code = ? and version = ?", product, version);
        if (headers.deleteDraft(product, version) == 0)
            throw Errors.conflict("ROUTING_NOT_DRAFT", "工艺路线已被并发发布: " + product + "/" + version);
    }

    /** 发布：工作中心必须存在；生效日须晚于最新已发布版本；发布后截断上一版本。 */
    @Transactional
    public Routing release(String product, String version) {
        // 并发控制：同一产品不同草稿并发发布时，“读取最新已发布版本 → 校验生效日 → 发布 → 截断上一版本”
        // 必须串行，否则两个事务都以同一旧版本为基准，产生两个 effective_to 为空的 RELEASED 版本。
        // 以产品物料行作为锁对象（FOR UPDATE），BOM 与工艺路线各自的发布也因此互斥，锁粒度为单个产品。
        if (!materials.lockForUpdate(product)) throw Errors.notFound("ROUTING_NOT_FOUND", "工艺路线不存在: " + product + "/" + version);
        Routing draft = get(product, version);
        if (draft.status() != LifecycleStatus.DRAFT) throw Errors.conflict("ROUTING_NOT_DRAFT", "只有草稿工艺路线可发布: " + product + "/" + version);
        for (Operation op : draft.operations())
            if (!workCenters.exists(op.workCenterCode()))
                throw Errors.rule("WORK_CENTER_NOT_FOUND", "工序 " + op.opNo() + " 引用的工作中心不存在: " + op.workCenterCode());
        Optional<VersionedHeaderDao.Header> previous = headers.latestReleased(product);
        if (previous.isPresent() && !draft.effectiveFrom().isAfter(previous.get().effectiveFrom()))
            throw Errors.conflict("EFFECTIVE_DATE_CONFLICT", "生效日必须晚于当前已发布版本 " + previous.get().version() + " 的生效日 " + previous.get().effectiveFrom());
        // 先截断上一版本再发布本版本：两步之间不会出现两个开放区间，满足 uk_*_current 唯一约束。
        previous.ifPresent(p -> headers.truncate(product, p.version(), draft.effectiveFrom()));
        try {
            if (headers.release(product, version) == 0) throw Errors.conflict("ROUTING_NOT_DRAFT", "工艺路线已被并发发布: " + product + "/" + version);
        } catch (org.springframework.dao.DuplicateKeyException e) {
            // 行锁之外的兜底（如绕过服务直接写库）：唯一约束拒绝第二个当前版本，整笔事务回滚。
            throw Errors.conflict("ROUTING_RELEASE_CONFLICT", "该产品已存在其他当前生效的已发布版本: " + product);
        }
        return get(product, version);
    }

    /** 工序号唯一。顺序由工序号数值决定，而非请求中的列表顺序。 */
    private static void validateOperations(SaveRouting r) {
        Set<String> seen = new HashSet<>();
        for (Operation op : r.operations())
            if (!seen.add(op.opNo())) throw Errors.rule("OPERATION_DUPLICATE", "工序号重复: " + op.opNo());
    }
    private void insertOperations(SaveRouting r) {
        try {
            for (Operation op : r.operations())
                jdbc.update("insert into routing_operation (product_code, version, op_no, seq, name, work_center_code, setup_minutes, run_seconds_per_unit, report_point, first_article_required, inspection_point_codes) values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                    r.productCode(), r.version(), op.opNo(), Integer.parseInt(op.opNo().substring(2)), op.name(), op.workCenterCode(),
                    op.setupMinutes(), op.runSecondsPerUnit(), op.reportPoint(), op.firstArticleRequired(), String.join(",", op.inspectionPointCodes()));
        } catch (DataIntegrityViolationException e) {
            // 外键兜底：草稿阶段即拒绝引用不存在的工作中心。
            throw Errors.rule("WORK_CENTER_NOT_FOUND", "工序引用了不存在的工作中心");
        }
    }
    private Routing toRouting(VersionedHeaderDao.Header h) {
        List<Operation> ops = jdbc.query("select op_no, name, work_center_code, setup_minutes, run_seconds_per_unit, report_point, first_article_required, inspection_point_codes from routing_operation where product_code = ? and version = ? order by seq",
            (rs, n) -> new Operation(rs.getString(1), rs.getString(2), rs.getString(3), rs.getInt(4), rs.getInt(5), rs.getBoolean(6), rs.getBoolean(7),
                rs.getString(8).isEmpty() ? List.of() : List.of(rs.getString(8).split(","))),
            h.productCode(), h.version());
        return new Routing(h.productCode(), h.version(), h.status(), h.effectiveFrom(), h.effectiveTo(), ops);
    }
}
