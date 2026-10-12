package com.smartmanufacturing.inventory;

import com.smartmanufacturing.inventory.ReservationDtos.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.*;

/**
 * 物料预留领域服务（契约见 docs/api/inventory-openapi.yaml）。所有写方法在 {@link IdempotencyService} 开启的事务内执行。
 * <p>
 * 关键约束：
 * <ul>
 *   <li><b>整单原子</b>：先按（物料、单位）汇总需求，按物料编码升序逐个锁定该物料全部现存量行（{@link StockService} 的统一加锁顺序），
 *       计算可用量后一次性判定；任何物料不足则整单不预留，409 列出全部缺口。</li>
 *   <li><b>状态迁移</b>只在锁定预留单头（SELECT ... FOR UPDATE）后进行，与过期任务（FOR UPDATE SKIP LOCKED）互斥：
 *       续期与过期并发时，要么续期成功、要么过期先生效后续期返回 410，不会出现“已过期却续期成功”。</li>
 *   <li><b>过期只由库存服务判定</b>：到期但过期任务尚未处理的预留单仍是 ACTIVE，可以续期和扣减。</li>
 *   <li>状态变化与事件在同一事务写入 outbox。</li>
 * </ul>
 */
@Service
public class ReservationService {
    private final JdbcTemplate jdbc;
    private final StockService stock;
    private final OutboxWriter outbox;
    private final Clock clock;
    private final Duration ttl;
    private final InventoryMetrics metrics;

    public ReservationService(JdbcTemplate jdbc, StockService stock, OutboxWriter outbox, Clock clock, InventoryMetrics metrics,
                              @Value("${inventory.reservation.ttl:PT15M}") Duration ttl) {
        this.jdbc = jdbc; this.stock = stock; this.outbox = outbox; this.clock = clock; this.ttl = ttl; this.metrics = metrics;
    }

    /** 预留单头（已加锁或快照）。 */
    private record Header(String id, String workOrderNo, Status status, Instant expiresAt) {}

    /** 校验并规范化后的需求行。 */
    private record Need(CreateLine line, BigDecimal qty) {}

    /**
     * 整单预留。
     * @throws ProblemException sourceLineId 重复、单位不一致或数量非法 400；任一物料不足 409 insufficient-stock
     */
    public Reservation create(String principal, CreateReservation request) {
        List<Need> needs = new ArrayList<>();
        Set<String> sourceIds = new HashSet<>();
        for (CreateLine l : request.lines()) {
            if (!sourceIds.add(l.sourceLineId())) throw ProblemException.validation("sourceLineId 重复：" + l.sourceLineId());
            needs.add(new Need(l, Quantities.parsePositive("lines.qty", l.qty())));
        }
        // 按（物料、单位）汇总；TreeMap 保证按物料编码升序加锁，避免两张预留单交叉加锁死锁。
        Map<String, List<Need>> byMaterial = new TreeMap<>();
        for (Need n : needs) byMaterial.computeIfAbsent(n.line().materialCode(), k -> new ArrayList<>()).add(n);

        List<Shortage> shortages = new ArrayList<>();
        for (Map.Entry<String, List<Need>> e : byMaterial.entrySet()) {
            String material = e.getKey();
            String registered = stock.findUom(material);
            // 同一物料的所有行单位必须一致，并与库存登记单位一致；未登记的物料没有库存，按可用量 0 计入缺口。
            Set<String> uoms = new TreeSet<>();
            e.getValue().forEach(n -> uoms.add(n.line().uom()));
            if (uoms.size() > 1) throw ProblemException.validation("物料 " + material + " 的需求行计量单位不一致：" + uoms);
            String uom = uoms.iterator().next();
            if (registered != null && !registered.equals(uom))
                // 与已登记的现存量单位不一致：请求格式合法但无法处理，返回 422 uom-mismatch（与入库口径一致）。
                throw ProblemException.uomMismatch(material, registered, uom);
            BigDecimal requested = e.getValue().stream().map(Need::qty).reduce(BigDecimal.ZERO, BigDecimal::add);
            BigDecimal available = BigDecimal.ZERO;
            if (registered != null) {
                List<StockService.LockedRow> rows = stock.lockMaterialRows(material);
                available = StockService.sum(rows).subtract(stock.activeReserved(material)).max(BigDecimal.ZERO);
            }
            if (requested.compareTo(available) > 0) {
                shortages.add(new Shortage(material, uom, Quantities.format(requested), Quantities.format(available),
                    Quantities.format(requested.subtract(available)), e.getValue().stream().map(n -> n.line().sourceLineId()).toList()));
            }
        }
        if (!shortages.isEmpty())
            throw new ProblemException(ProblemType.INSUFFICIENT_STOCK, "可用量不足，整单未预留", Map.of("shortages", shortages));

        String id = UUID.randomUUID().toString();
        Instant now = clock.instant();
        Instant expiresAt = now.plus(ttl);
        jdbc.update("insert into reservation (id, work_order_no, status, expires_at, principal, version, created_at, updated_at) values (?, ?, 'ACTIVE', ?, ?, 0, ?, ?)",
            id, request.workOrderNo(), Timestamp.from(expiresAt), principal, Timestamp.from(now), Timestamp.from(now));
        int lineNo = 0;
        for (Need n : needs) {
            jdbc.update("insert into reservation_line (id, reservation_id, line_no, source_line_id, material_code, uom, qty, reserved_qty, consumed_qty, status)"
                + " values (?, ?, ?, ?, ?, ?, ?, ?, 0, 'OPEN')", UUID.randomUUID().toString(), id, ++lineNo, n.line().sourceLineId(),
                n.line().materialCode(), n.line().uom(), n.qty(), n.qty());
        }
        Reservation result = load(id);
        metrics.transition(Status.ACTIVE);
        outbox.append("ReservationCreated", id, request.workOrderNo(), "ACTIVE", Map.of("expiresAt", expiresAt, "lines", result.lines()));
        return result;
    }

    /** 查询预留单；不存在 404。 */
    public Reservation get(String id) { return load(id); }

    /** 整单续期：仅 ACTIVE 可续期，expiresAt 重置为当前时间 + TTL。 */
    public Reservation renew(String id) {
        Header h = lock(id);
        requireActive(h, ProblemType.RESERVATION_CONSUMED);
        Instant now = clock.instant();
        jdbc.update("update reservation set expires_at = ?, version = version + 1, updated_at = ? where id = ?",
            Timestamp.from(now.plus(ttl)), Timestamp.from(now), id);
        return load(id);
    }

    /**
     * 整单释放（幂等）：ACTIVE → RELEASED 并发事件；已 RELEASED/EXPIRED 直接返回当前状态（占用已归还），不重复发事件；CONSUMED 返回 409。
     */
    public Reservation release(String id, ReleaseReason reason) {
        Header h = lock(id);
        if (h.status() == Status.CONSUMED) throw new ProblemException(ProblemType.RESERVATION_CONSUMED, "预留单已整单扣减，不能释放");
        if (h.status() != Status.ACTIVE) return load(id);
        ReleaseReason r = reason == null ? ReleaseReason.CALLER_REQUEST : reason;
        jdbc.update("update reservation set status = 'RELEASED', release_reason = ?, version = version + 1, updated_at = ? where id = ?",
            r.name(), Timestamp.from(clock.instant()), id);
        metrics.transition(Status.RELEASED);
        outbox.append("ReservationReleased", id, h.workOrderNo(), "RELEASED", Map.of("reason", r.name()));
        return load(id);
    }

    /**
     * 按行扣减：从指定批次（按库存行 id 升序跨库位）扣减现存量并写 CONSUME 流水；
     * 行剩余量为 0 时关闭该行，全部行关闭后预留单变为 CONSUMED 并发事件。
     */
    public Reservation consume(String id, String lineId, ConsumeRequest request) {
        BigDecimal qty = Quantities.parsePositive("qty", request.qty());
        Header h = lock(id);
        requireActive(h, ProblemType.RESERVATION_CONSUMED);
        List<Map<String, Object>> lines = jdbc.queryForList(
            "select material_code, uom, reserved_qty, consumed_qty, status, source_line_id from reservation_line where id = ? and reservation_id = ?", lineId, id);
        if (lines.isEmpty()) throw new NotFoundException("预留行不存在：" + lineId);
        Map<String, Object> line = lines.get(0);
        if ("CLOSED".equals(line.get("status"))) throw new ProblemException(ProblemType.LINE_CLOSED, "预留行已关闭");
        String material = (String) line.get("material_code");
        String uom = (String) line.get("uom");
        BigDecimal remaining = ((BigDecimal) line.get("reserved_qty")).subtract((BigDecimal) line.get("consumed_qty"));
        if (qty.compareTo(remaining) > 0)
            throw new ProblemException(ProblemType.OVER_CONSUME, "扣减数量 " + Quantities.format(qty) + " 超过剩余可扣减量 " + Quantities.format(remaining));

        // 与出库、预留相同的加锁顺序：锁定该物料全部现存量行后，从指定批次扣减。
        List<StockService.LockedRow> lotRows = stock.lockMaterialRows(material).stream()
            .filter(r -> r.lotNo().equals(request.lotNo()) && r.qty().signum() > 0).toList();
        BigDecimal lotQty = StockService.sum(lotRows);
        if (qty.compareTo(lotQty) > 0) {
            // 预留保证的是物料总量，指定批次余额不足时按缺口返回，调用方可换批次重试。
            throw new ProblemException(ProblemType.INSUFFICIENT_STOCK, "批次 " + request.lotNo() + " 余额不足",
                Map.of("shortages", List.of(new Shortage(material, uom, Quantities.format(qty), Quantities.format(lotQty),
                    Quantities.format(qty.subtract(lotQty)), List.of((String) line.get("source_line_id"))))));
        }
        BigDecimal left = qty;
        for (StockService.LockedRow row : lotRows) {
            if (left.signum() == 0) break;
            BigDecimal take = left.min(row.qty());
            stock.applyDelta(row, material, uom, take.negate(), StockService.CONSUME, "RESERVATION", id + ":" + lineId, "reservation:" + id);
            left = left.subtract(take);
        }
        boolean closed = qty.compareTo(remaining) == 0;
        jdbc.update("update reservation_line set consumed_qty = consumed_qty + ?, status = ? where id = ?", qty, closed ? "CLOSED" : "OPEN", lineId);
        Integer open = jdbc.queryForObject("select count(*) from reservation_line where reservation_id = ? and status = 'OPEN'", Integer.class, id);
        if (open != null && open == 0) {
            jdbc.update("update reservation set status = 'CONSUMED', version = version + 1, updated_at = ? where id = ?", Timestamp.from(clock.instant()), id);
            Reservation result = load(id);
            metrics.transition(Status.CONSUMED);
            outbox.append("ReservationConsumed", id, h.workOrderNo(), "CONSUMED", Map.of("lines", result.lines()));
            return result;
        }
        jdbc.update("update reservation set version = version + 1, updated_at = ? where id = ?", Timestamp.from(clock.instant()), id);
        return load(id);
    }

    /**
     * 过期一张到期的 ACTIVE 预留单（由过期任务在 SKIP LOCKED 选出并已加锁后调用）：状态置 EXPIRED，占用随即不再计入可用量，并发事件。
     * @return 是否实际过期（加锁后再次核对状态与到期时间，防止期间被续期或释放）
     */
    boolean expireLocked(String id, Instant now) {
        int n = jdbc.update("update reservation set status = 'EXPIRED', version = version + 1, updated_at = ? where id = ? and status = 'ACTIVE' and expires_at < ?",
            Timestamp.from(now), id, Timestamp.from(now));
        if (n == 0) return false;
        Header h = jdbc.queryForObject("select id, work_order_no, status, expires_at from reservation where id = ?", (rs, i) -> header(rs), id);
        metrics.transition(Status.EXPIRED);
        outbox.append("ReservationExpired", id, h.workOrderNo(), "EXPIRED", Map.of("expiresAt", h.expiresAt()));
        return true;
    }

    /** 终态检查：过期、释放返回 410；整单扣减返回指定类型（409）。 */
    private static void requireActive(Header h, ProblemType whenConsumed) {
        switch (h.status()) {
            case EXPIRED -> throw new ProblemException(ProblemType.RESERVATION_EXPIRED, "预留单已过期，需重新预留");
            case RELEASED -> throw new ProblemException(ProblemType.RESERVATION_RELEASED, "预留单已释放，需重新预留");
            case CONSUMED -> throw new ProblemException(whenConsumed, "预留单已整单扣减");
            case ACTIVE -> { }
        }
    }

    private Header lock(String id) {
        List<Header> rows = jdbc.query("select id, work_order_no, status, expires_at from reservation where id = ? for update", (rs, i) -> header(rs), id);
        if (rows.isEmpty()) throw new NotFoundException("预留单不存在：" + id);
        return rows.get(0);
    }

    private static Header header(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new Header(rs.getString(1), rs.getString(2), Status.valueOf(rs.getString(3)), rs.getTimestamp(4).toInstant());
    }

    private Reservation load(String id) {
        List<Header> rows = jdbc.query("select id, work_order_no, status, expires_at from reservation where id = ?", (rs, i) -> header(rs), id);
        if (rows.isEmpty()) throw new NotFoundException("预留单不存在：" + id);
        Header h = rows.get(0);
        List<Line> lines = jdbc.query("select id, source_line_id, material_code, uom, qty, reserved_qty, consumed_qty, status from reservation_line"
                + " where reservation_id = ? order by line_no",
            (rs, i) -> new Line(rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4), Quantities.format(rs.getBigDecimal(5)),
                Quantities.format(rs.getBigDecimal(6)), Quantities.format(rs.getBigDecimal(7)), rs.getString(8)), id);
        return new Reservation(h.id(), h.workOrderNo(), h.status(), h.expiresAt(), lines);
    }
}
