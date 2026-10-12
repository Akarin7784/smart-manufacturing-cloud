package com.smartmanufacturing.inventory;

import com.smartmanufacturing.inventory.InventoryDtos.*;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * 现存量与出入库流水的领域服务。所有写方法都必须在调用方开启的事务内执行（由 {@link IdempotencyService} 提供）。
 * <p>
 * 并发与加锁约定（预留、扣减在后续 PR 中复用同一约定）：
 * <ul>
 *   <li>需要校验“可用量”的写操作（出库、预留）对该物料的<b>全部</b>现存量行执行 {@code SELECT ... FOR UPDATE}，按 id 升序加锁；
 *       涉及多个物料时按物料编码升序逐个加锁。所有写路径遵循同一顺序，避免死锁。</li>
 *   <li>入库只锁定（或新建）单个现存量行；入库只会增加可用量，不需要锁住同物料的其他行。</li>
 *   <li>可用量 = 现存量合计 − ACTIVE 预留单中 OPEN 行的未扣减量（reserved_qty − consumed_qty）。</li>
 *   <li>现存量行更新同时带 version 条件并加一：已持有行锁时条件必然成立，作为防御性校验与变更计数。</li>
 *   <li>每次余额变化都追加一条流水（只追加、不修改），流水与余额在同一事务提交，可随时对账。</li>
 * </ul>
 */
@Service
public class StockService {
    /** 流水类型：入库、出库；预留扣减（CONSUME）由预留 PR 使用。 */
    public static final String INBOUND = "INBOUND", OUTBOUND = "OUTBOUND", CONSUME = "CONSUME";
    /** 质检隔离流水类型（refType=NCR）：入隔离、放行出库、报废出库。 */
    public static final String QC_HOLD_IN = "QC_HOLD_IN", QC_HOLD_RELEASE = "QC_HOLD_RELEASE", SCRAP = "SCRAP";
    /** 质检隔离系统库位编码（V2 迁移种子）；其余额为隔离量，不计入可用量、不可预留。 */
    public static final String QC_HOLD = "QC-HOLD";

    private final JdbcTemplate jdbc;
    private final LocationService locations;
    private final Clock clock;
    private final InventoryMetrics metrics;

    public StockService(JdbcTemplate jdbc, LocationService locations, Clock clock, InventoryMetrics metrics) {
        this.jdbc = jdbc; this.locations = locations; this.clock = clock; this.metrics = metrics;
    }

    /** 已加锁的现存量行。 */
    record LockedRow(long id, String locationCode, String lotNo, BigDecimal qty, long version) {}

    /** 入库：登记/校验物料单位与批次，增加（或新建）对应库位批次的现存量，并写入一条正数流水。 */
    public StockTxn inbound(String operator, Movement r) {
        BigDecimal qty = Quantities.parsePositive("qty", r.qty());
        // 通用入库只能用于普通库位；系统库位（QC-HOLD）只能经 /inventory/qc-hold/** 变动。
        locations.requireGeneral(r.locationCode());
        ensureUom(r.materialCode(), r.uom());
        ensureLot(r.materialCode(), r.lotNo());
        LockedRow row = lockOrCreateRow(r.materialCode(), r.locationCode(), r.lotNo());
        return applyDelta(row, r.materialCode(), r.uom(), qty, INBOUND, "EXTERNAL", r.refNo(), operator);
    }

    /**
     * 出库：校验库位批次余额足够，且出库后不会侵占 ACTIVE 预留（物料现存量合计 − 出库量 ≥ 有效预留量）。
     * 不足返回 409 insufficient-stock，shortages 给出该物料的需求、可用与缺口。
     */
    public StockTxn outbound(String operator, Movement r) {
        BigDecimal qty = Quantities.parsePositive("qty", r.qty());
        // 通用出库同样拒绝系统库位，防止绕过质检直接出隔离品。
        locations.requireSystemExcluded(r.locationCode());
        String uom = requireUom(r.materialCode(), r.uom());
        List<LockedRow> rows = lockMaterialRows(r.materialCode());
        LockedRow row = rows.stream().filter(x -> x.locationCode().equals(r.locationCode()) && x.lotNo().equals(r.lotNo()))
            .findFirst().orElse(null);
        BigDecimal rowQty = row == null ? BigDecimal.ZERO : row.qty();
        BigDecimal free = sum(rows).subtract(activeReserved(r.materialCode()));
        // 实际可出 = min(该库位批次余额, 物料总可用量)：前者保证余额不为负，后者保证不侵占其他工单的预留。
        BigDecimal available = rowQty.min(free).max(BigDecimal.ZERO);
        if (qty.compareTo(available) > 0) {
            throw new ProblemException(ProblemType.INSUFFICIENT_STOCK, "出库数量超过可用量",
                Map.of("shortages", List.of(new StockShortage(r.materialCode(), uom, Quantities.format(qty),
                    Quantities.format(available), Quantities.format(qty.subtract(available))))));
        }
        return applyDelta(row, r.materialCode(), uom, qty.negate(), OUTBOUND, "EXTERNAL", r.refNo(), operator);
    }

    /**
     * 物料首次入库时登记计量单位；之后的入库必须使用相同单位，防止 kg 与 件 混记在同一余额上。
     * 并发首次登记由主键裁决，失败方重新读取后比较。
     */
    void ensureUom(String material, String uom) {
        String existing = findUom(material);
        if (existing == null) {
            try {
                jdbc.update("insert into inventory_material (material_code, uom) values (?, ?)", material, uom);
                return;
            } catch (DuplicateKeyException race) {
                // 必须用加锁读：REPEATABLE READ 下普通快照读看不到对方刚提交的行，加锁读总是读取最新已提交版本。
                existing = jdbc.queryForObject("select uom from inventory_material where material_code = ? for update", String.class, material);
            }
        }
        if (!existing.equals(uom)) throw ProblemException.uomMismatch(material, existing, uom);
    }

    /**
     * 出库、预留共用的单位口径（两处必须一致）：
     * <ul>
     *   <li>物料从未入过库（未登记单位）：不报单位错误，返回请求单位，由调用方按可用量 0 处理并返回 409 insufficient-stock；</li>
     *   <li>已有现存量登记且单位不一致：422 uom-mismatch；</li>
     *   <li>单位一致：返回登记单位。</li>
     * </ul>
     */
    String requireUom(String material, String uom) {
        String existing = findUom(material);
        if (existing == null) return uom;
        if (!existing.equals(uom)) throw ProblemException.uomMismatch(material, existing, uom);
        return existing;
    }

    String findUom(String material) {
        List<String> rows = jdbc.query("select uom from inventory_material where material_code = ?", (rs, n) -> rs.getString(1), material);
        return rows.isEmpty() ? null : rows.get(0);
    }

    /** 批次首次出现时登记；并发登记的主键冲突说明已存在，直接忽略。 */
    private void ensureLot(String material, String lotNo) {
        Integer n = jdbc.queryForObject("select count(*) from lot where material_code = ? and lot_no = ?", Integer.class, material, lotNo);
        if (n != null && n > 0) return;
        try {
            jdbc.update("insert into lot (material_code, lot_no) values (?, ?)", material, lotNo);
        } catch (DuplicateKeyException ignored) {
            // 另一个事务刚登记了同一批次，结果等价。
        }
    }

    /**
     * 质检入隔离：登记/校验单位与批次后，增加 QC-HOLD 库位该批次余额。只锁 QC-HOLD 的单行，
     * 不锁同物料的普通库位行：隔离量不参与可用量计算，单行锁与其他写路径不会形成环形等待。
     */
    public StockTxn qcHoldIn(String operator, String material, String uom, String lotNo, BigDecimal qty, String ncrNo) {
        ensureUom(material, uom);
        ensureLot(material, lotNo);
        LockedRow row = lockOrCreateRow(material, QC_HOLD, lotNo);
        return applyDelta(row, material, uom, qty, QC_HOLD_IN, "NCR", ncrNo, operator);
    }

    /**
     * 质检出隔离（放行或报废）：只从 QC-HOLD 该批次扣减，余额不足返回 409 insufficient-stock（说明质检与库存不一致）。
     * 单位按出库口径校验（未登记单位视为余额 0）。
     */
    public StockTxn qcHoldOut(String operator, String material, String uom, String lotNo, BigDecimal qty, String type, String ncrNo) {
        String registered = requireUom(material, uom);
        LockedRow row = lockRow(material, QC_HOLD, lotNo);
        BigDecimal held = row == null ? BigDecimal.ZERO : row.qty();
        if (qty.compareTo(held) > 0) {
            throw new ProblemException(ProblemType.INSUFFICIENT_STOCK, "QC-HOLD 隔离量不足",
                Map.of("shortages", List.of(new StockShortage(material, registered, Quantities.format(qty),
                    Quantities.format(held), Quantities.format(qty.subtract(held))))));
        }
        return applyDelta(row, material, registered, qty.negate(), type, "NCR", ncrNo, operator);
    }

    /** 锁定单个库位批次行；不存在则以 0 余额新建后再锁定（并发新建由唯一约束裁决，失败方锁定胜者的行）。 */
    private LockedRow lockOrCreateRow(String material, String location, String lotNo) {
        LockedRow row = lockRow(material, location, lotNo);
        if (row != null) return row;
        try {
            jdbc.update("insert into stock_on_hand (material_code, location_code, lot_no, qty, version, updated_at) values (?, ?, ?, 0, 0, ?)",
                material, location, lotNo, Timestamp.from(clock.instant()));
        } catch (DuplicateKeyException race) {
            // 并发入库同一新批次：对方已插入，下面直接锁定该行。
        }
        return lockRow(material, location, lotNo);
    }

    private LockedRow lockRow(String material, String location, String lotNo) {
        List<LockedRow> rows = metrics.timeLock(() -> jdbc.query("select id, location_code, lot_no, qty, version from stock_on_hand"
            + " where material_code = ? and location_code = ? and lot_no = ? for update", (rs, n) -> map(rs), material, location, lotNo));
        return rows.isEmpty() ? null : rows.get(0);
    }

    /**
     * 按 id 升序锁定物料的全部<b>普通库位</b>现存量行（固定加锁顺序，见类注释）。
     * 排除 QC-HOLD：隔离量不计入可用量，出库、预留、扣减都不能使用它。
     * 用库位编码 {@code location_code <> 'QC-HOLD'} 而不是 join warehouse_location 判断 system_location：
     * 加锁查询只扫 stock_on_hand 的物料索引，不连带锁住库位表的行；QC-HOLD 是 V2 种子的唯一系统库位，编码固定不变，
     * 与隔离接口、可用量查询使用同一常量 {@link #QC_HOLD}，口径一致。今后若新增其他系统库位，再改为按 system_location 判断。
     */
    List<LockedRow> lockMaterialRows(String material) {
        return metrics.timeLock(() -> jdbc.query("select id, location_code, lot_no, qty, version from stock_on_hand where material_code = ? and location_code <> ? order by id for update",
            (rs, n) -> map(rs), material, QC_HOLD));
    }

    /** 物料的有效预留量：ACTIVE 预留单中 OPEN 行的未扣减部分之和。 */
    BigDecimal activeReserved(String material) {
        BigDecimal v = jdbc.queryForObject("select coalesce(sum(l.reserved_qty - l.consumed_qty), 0) from reservation_line l"
            + " join reservation r on r.id = l.reservation_id where l.material_code = ? and l.status = 'OPEN' and r.status = 'ACTIVE'",
            BigDecimal.class, material);
        return v == null ? BigDecimal.ZERO : v;
    }

    static BigDecimal sum(List<LockedRow> rows) {
        return rows.stream().map(LockedRow::qty).reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    /**
     * 对已加锁的行应用带符号的变化量并追加流水。调用方已保证结果非负；数据库 CHECK 约束兜底。
     * @return 新写入的流水
     */
    StockTxn applyDelta(LockedRow row, String material, String uom, BigDecimal delta, String type, String refType, String refNo, String operator) {
        BigDecimal balance = row.qty().add(delta);
        Instant now = clock.instant();
        int updated = jdbc.update("update stock_on_hand set qty = ?, version = version + 1, updated_at = ? where id = ? and version = ?",
            balance, Timestamp.from(now), row.id(), row.version());
        if (updated != 1) throw new IllegalStateException("现存量行版本不一致，行锁约定被破坏：id=" + row.id());
        org.springframework.jdbc.support.GeneratedKeyHolder keys = new org.springframework.jdbc.support.GeneratedKeyHolder();
        jdbc.update(con -> {
            var ps = con.prepareStatement("insert into stock_txn (txn_type, material_code, location_code, lot_no, qty, balance_after, ref_type, ref_no, operator, occurred_at)"
                + " values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)", new String[] {"id"});
            ps.setString(1, type); ps.setString(2, material); ps.setString(3, row.locationCode()); ps.setString(4, row.lotNo());
            ps.setBigDecimal(5, delta); ps.setBigDecimal(6, balance); ps.setString(7, refType); ps.setString(8, refNo);
            ps.setString(9, operator); ps.setTimestamp(10, Timestamp.from(now));
            return ps;
        }, keys);
        metrics.movement(type, uom, delta);
        return new StockTxn(keys.getKey().longValue(), type, material, uom, row.locationCode(), row.lotNo(),
            Quantities.format(delta), Quantities.format(balance), refType, refNo, operator, now);
    }

    /** 现存量查询：materialCode 必填，locationCode 可选；只返回余额大于 0 的行。 */
    public List<Stock> stock(String material, String location) {
        // inQcHold 由库位编码判定；隔离行照常列出，便于查看隔离量的批次分布。
        return jdbc.query("select s.material_code, m.uom, s.location_code, s.lot_no, s.qty, s.version from stock_on_hand s"
                + " join inventory_material m on m.material_code = s.material_code"
                + " where s.material_code = ? and (? is null or s.location_code = ?) and s.qty > 0 order by s.location_code, s.lot_no",
            (rs, n) -> new Stock(rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4), Quantities.format(rs.getBigDecimal(5)),
                QC_HOLD.equals(rs.getString(3)), rs.getLong(6)),
            material, location, location);
    }

    /** 可用量查询（不加锁的快照读，仅供展示；写操作以加锁后的计算为准）。 */
    public Availability availability(String material) {
        String uom = findUom(material);
        if (uom == null) throw new NotFoundException("物料没有库存记录：" + material);
        // 现存量合计不含 QC-HOLD；隔离量单独汇总返回。
        BigDecimal onHand = jdbc.queryForObject("select coalesce(sum(qty), 0) from stock_on_hand where material_code = ? and location_code <> ?", BigDecimal.class, material, QC_HOLD);
        BigDecimal quarantined = jdbc.queryForObject("select coalesce(sum(qty), 0) from stock_on_hand where material_code = ? and location_code = ?", BigDecimal.class, material, QC_HOLD);
        BigDecimal reserved = activeReserved(material);
        return new Availability(material, uom, Quantities.format(onHand), Quantities.format(reserved), Quantities.format(onHand.subtract(reserved)),
            Quantities.format(quarantined));
    }

    /** 批次查询。 */
    public List<Lot> lots(String material) {
        return jdbc.query("select material_code, lot_no, created_at from lot where material_code = ? order by lot_no",
            (rs, n) -> new Lot(rs.getString(1), rs.getString(2), rs.getTimestamp(3).toInstant()), material);
    }

    /** 流水查询：按时间倒序，limit 限制在 1–500，防止一次拉取过多。 */
    public List<StockTxn> ledger(String material, String lotNo, int limit) {
        if (limit < 1 || limit > 500) throw ProblemException.validation("limit 必须在 1–500 之间");
        return jdbc.query("select t.id, t.txn_type, t.material_code, m.uom, t.location_code, t.lot_no, t.qty, t.balance_after, t.ref_type, t.ref_no, t.operator, t.occurred_at"
                + " from stock_txn t join inventory_material m on m.material_code = t.material_code"
                + " where t.material_code = ? and (? is null or t.lot_no = ?) order by t.id desc limit " + limit,
            (rs, n) -> new StockTxn(rs.getLong(1), rs.getString(2), rs.getString(3), rs.getString(4), rs.getString(5), rs.getString(6),
                Quantities.format(rs.getBigDecimal(7)), Quantities.format(rs.getBigDecimal(8)), rs.getString(9), rs.getString(10),
                rs.getString(11), rs.getTimestamp(12).toInstant()),
            material, lotNo, lotNo);
    }

    private static LockedRow map(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new LockedRow(rs.getLong(1), rs.getString(2), rs.getString(3), rs.getBigDecimal(4), rs.getLong(5));
    }
}
