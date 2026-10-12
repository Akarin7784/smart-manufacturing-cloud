package com.smartmanufacturing.workorder;
import com.smartmanufacturing.api.ApiResponse;
import com.smartmanufacturing.api.masterdata.BomExplosion;
import com.smartmanufacturing.api.masterdata.BomExplosionLine;
import com.smartmanufacturing.api.masterdata.Operation;
import com.smartmanufacturing.api.masterdata.Routing;
import com.smartmanufacturing.api.workorder.*;
import com.smartmanufacturing.common.BusinessException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.*;
import java.util.function.Supplier;

/**
 * 工单生命周期应用服务：下达（CREATED→RELEASED）、冻结 / 恢复（ON_HOLD，resume_target）、预留过期处理。
 * <p>
 * 事务边界：主数据查询与库存预留等远程调用都在本地事务<b>之外</b>；状态、快照、预留镜像、审计日志与发件箱事件
 * 在同一本地事务中提交。本地事务失败时补偿释放预留（释放失败由库存 TTL 15 分钟兜底）。不使用分布式事务。
 * <p>
 * 并发控制：
 * <ul>
 *   <li>下达：work_order_lifecycle 主键 + 条件更新 {@code status='CREATED'} 裁决，只有一个下达请求能成功；
 *       相同幂等键的并发重试返回首次结果（共用同一预留），不同键的失败方释放自己的预留并返回 409。</li>
 *   <li>冻结 / 恢复：lifecycle.version 乐观锁，冲突时重读重试，超过次数返回 409 CONCURRENT_MODIFICATION。</li>
 * </ul>
 */
@Service
public class WorkOrderReleaseService {
    private static final Logger LOG = LoggerFactory.getLogger(WorkOrderReleaseService.class);
    /** 乐观锁冲突的最大重试次数。 */
    static final int MAX_ATTEMPTS = 5;
    /** 允许冻结的状态（设计文档 3.1）。 */
    private static final Set<String> HOLDABLE = Set.of("RELEASED", "IN_PROGRESS");

    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;
    private final MasterdataClient masterdata;
    private final MaterialReservationPort materials;
    private final WorkOrderJournal journal;
    private final Clock clock;

    public WorkOrderReleaseService(JdbcTemplate jdbc, TransactionTemplate tx, MasterdataClient masterdata,
                                   MaterialReservationPort materials, WorkOrderJournal journal, Clock clock) {
        this.jdbc = jdbc; this.tx = tx; this.masterdata = masterdata; this.materials = materials; this.journal = journal; this.clock = clock;
    }

    /** 下达结果：replayed=true 表示同一幂等键的重放，未产生新写入。 */
    public record ReleaseResult(WorkOrderReleaseView view, boolean replayed) {}

    private record Order(String id, String product, int quantity, String status) {}
    private record Lifecycle(long version, String resumeTarget, String releaseKey) {}
    /** 乐观锁冲突信号：仅在内部用于触发整笔事务回滚并重试。 */
    private static final class VersionConflict extends RuntimeException {
        VersionConflict() { super(null, null, false, false); }
    }

    // ------------------------------------------------------------------ 下达

    /**
     * 幂等下达工单：校验产品已发布生效的 BOM 与工艺路线，冻结 BOM 展开与工艺快照，生成工序任务并整单预留物料。
     * 下达日期按工厂时区的当天计算；数量取工单数量。
     *
     * @throws BusinessException 工单不存在 404；状态不是 CREATED 或已被其他键下达 409；主数据无效 422；
     *                           物料短缺 409 MATERIAL_SHORTAGE（由库存适配器抛出）；依赖不可用 503
     */
    public ReleaseResult release(String orderId, String key, String operator) {
        Order order = order(orderId);
        Optional<ReleaseResult> replay = replay(orderId, key);
        if (replay.isPresent()) return replay.get();
        if (!"CREATED".equals(order.status()))
            throw new BusinessException("WORK_ORDER_STATE_CONFLICT", "只有 CREATED 状态的工单可以下达，当前为 " + order.status(), HttpStatus.CONFLICT);

        LocalDate date = LocalDate.now(clock);
        BomExplosion bom = data(masterdata.explode(order.product(), date.toString(), BigDecimal.valueOf(order.quantity())), "BOM");
        Routing routing = data(masterdata.effectiveRouting(order.product(), date.toString()), "工艺路线");
        if (bom.lines() == null || bom.lines().isEmpty() || routing.operations() == null || routing.operations().isEmpty())
            throw new BusinessException("RELEASE_MASTERDATA_INVALID", "BOM 或工艺路线为空，不能下达", HttpStatus.UNPROCESSABLE_ENTITY);
        List<ReservationLineView> lines = requirements(bom.lines());
        validateConsumeOps(bom.lines(), routing.operations());

        // 预留幂等键 = release:{工单号}:{下达 Idempotency-Key}（与库存约定）：同键重试 / 并发拿到同一张预留单，不同键各自独立。
        // 预留 409（缺料）由适配器抛出 MaterialShortageException，此时尚未写本地任何数据，工单保持 CREATED，缺料明细原样返回。
        String reservationKey = reservationKey(orderId, key);
        MaterialReservationPort.Reservation reservation = materials.reserve(reservationKey, orderId, lines);
        try {
            tx.executeWithoutResult(s -> persistRelease(order, key, operator, bom, routing, reservation));
            return new ReleaseResult(view(orderId), false);
        } catch (DuplicateKeyException race) {
            // 并发下达：生命周期主键或预留单号冲突，说明另一事务已提交下达。
            // 注意先比较键再决定是否释放：不能直接调用 replay()，它对不同键抛 409 会跳过补偿而泄漏预留。
            Optional<Lifecycle> winner = lifecycle(orderId);
            if (winner.isPresent() && winner.get().releaseKey().equals(key))
                return new ReleaseResult(view(orderId), true); // 同键：与胜者共用预留，不释放
            compensate(reservationKey, reservation.reservationId(), race);
            // 没有胜者说明是其他唯一约束冲突（非下达竞争），按原始异常抛出，不误报为状态冲突。
            if (winner.isEmpty()) throw race;
            throw new BusinessException("WORK_ORDER_STATE_CONFLICT", "工单已被其他请求下达", HttpStatus.CONFLICT);
        } catch (RuntimeException e) {
            compensate(reservationKey, reservation.reservationId(), e);
            throw e;
        }
    }

    /** 下达预留的幂等键：由工单号与下达请求的 Idempotency-Key 确定性派生。 */
    static String reservationKey(String orderId, String releaseKey) { return "release:" + orderId + ":" + releaseKey; }

    private void persistRelease(Order order, String key, String operator, BomExplosion bom, Routing routing,
                                MaterialReservationPort.Reservation reservation) {
        Timestamp now = Timestamp.from(clock.instant());
        // 先插入生命周期：主键裁决并发下达（失败方在此抛 DuplicateKeyException 并整笔回滚）。
        jdbc.update("insert into work_order_lifecycle (order_id, version, resume_target, product_code, bom_version, routing_version, release_key, released_by, released_at) values (?, 0, null, ?, ?, ?, ?, ?, ?)",
            order.id(), order.product(), bom.bomVersion(), routing.version(), key, operator, now);
        // 条件更新兜底：工单在校验之后被取消等情况下拒绝下达。
        if (jdbc.update("update work_order set status = 'RELEASED' where id = ? and status = 'CREATED'", order.id()) != 1)
            throw new BusinessException("WORK_ORDER_STATE_CONFLICT", "工单状态已变化，下达取消", HttpStatus.CONFLICT);
        int seq = 0;
        for (BomExplosionLine l : bom.lines())
            jdbc.update("insert into wo_bom_snapshot (order_id, seq, level, path, parent_code, component_code, qty_per, required_qty, uom, issue_mode, consume_op_no, leaf, source_bom_version) values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                order.id(), ++seq, l.level(), l.path(), l.parentCode(), l.componentCode(), l.qtyPer(), l.requiredQty(), l.uom(),
                l.issueMode().name(), l.consumeOpNo(), l.leaf(), l.sourceBomVersion());
        int opSeq = 0;
        for (Operation op : routing.operations()) {
            // 首道工序 READY，其余 PENDING（设计文档 3.2）。
            jdbc.update("insert into operation_task (order_id, op_no, seq, name, work_center_code, setup_minutes, run_seconds_per_unit, report_point, first_article_required, inspection_point_codes, status) values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                order.id(), op.opNo(), ++opSeq, op.name(), op.workCenterCode(), op.setupMinutes(), op.runSecondsPerUnit(),
                op.reportPoint(), op.firstArticleRequired(), String.join(",", op.inspectionPointCodes()), opSeq == 1 ? "READY" : "PENDING");
        }
        insertReservation(order.id(), 1, reservation);
        journal.statusChanged(order.id(), "CREATED", "RELEASED", null, null, operator);
        journal.event(order.id(), "WorkOrderReleased", Map.of(
            "productCode", order.product(), "quantity", order.quantity(), "bomVersion", bom.bomVersion(),
            "routingVersion", routing.version(), "reservationId", reservation.reservationId()));
    }

    /** 数量精度：与库存契约及 V2 的 DECIMAL(18,6) 一致。 */
    static final int QTY_SCALE = 6;

    /**
     * 叶子件按物料汇总为预留行（虚拟件已由主数据展开为其子件；非叶子的自制件不预留）。
     * 数量统一为 scale 6；超过 6 位小数直接拒绝（RoundingMode.UNNECESSARY），不静默截断。
     */
    static List<ReservationLineView> requirements(List<BomExplosionLine> lines) {
        Map<String, ReservationLineView> sum = new TreeMap<>();
        for (BomExplosionLine l : lines) {
            if (!l.leaf()) continue;
            sum.merge(l.componentCode(), new ReservationLineView(null, l.componentCode(), scale6(l.requiredQty()), l.uom()), (a, b) -> {
                if (!a.uom().equals(b.uom()))
                    throw new BusinessException("RELEASE_MASTERDATA_INVALID", "物料 " + a.materialCode() + " 在 BOM 中单位不一致", HttpStatus.UNPROCESSABLE_ENTITY);
                return new ReservationLineView(null, a.materialCode(), a.quantity().add(b.quantity()), a.uom());
            });
        }
        if (sum.isEmpty())
            throw new BusinessException("RELEASE_MASTERDATA_INVALID", "BOM 没有可预留的叶子物料", HttpStatus.UNPROCESSABLE_ENTITY);
        return List.copyOf(sum.values());
    }

    /** 规范为 scale 6；超精度属于主数据错误。 */
    static BigDecimal scale6(BigDecimal q) {
        try { return q.setScale(QTY_SCALE, java.math.RoundingMode.UNNECESSARY); }
        catch (ArithmeticException e) {
            throw new BusinessException("RELEASE_MASTERDATA_INVALID", "数量超过 6 位小数: " + q, HttpStatus.UNPROCESSABLE_ENTITY);
        }
    }

    /**
     * 第 1 层 BOM 行指定的消耗工序必须存在于成品工艺路线中，否则后续按工序发料无法落地。
     * 更深层的行引用的是其父组件自身工艺路线的工序（组件另行开工单），不在此校验。
     */
    static void validateConsumeOps(List<BomExplosionLine> lines, List<Operation> ops) {
        Set<String> opNos = new HashSet<>();
        ops.forEach(o -> opNos.add(o.opNo()));
        for (BomExplosionLine l : lines)
            if (l.level() == 1 && l.consumeOpNo() != null && !opNos.contains(l.consumeOpNo()))
                throw new BusinessException("RELEASE_MASTERDATA_INVALID", "物料 " + l.componentCode() + " 的消耗工序 " + l.consumeOpNo() + " 不在工艺路线中", HttpStatus.UNPROCESSABLE_ENTITY);
    }

    private Optional<ReleaseResult> replay(String orderId, String key) {
        return lifecycle(orderId).map(lc -> {
            if (!lc.releaseKey().equals(key))
                throw new BusinessException("WORK_ORDER_STATE_CONFLICT", "工单已被其他请求下达", HttpStatus.CONFLICT);
            return new ReleaseResult(view(orderId), true);
        });
    }

    private <T> T data(ApiResponse<T> response, String what) {
        if (response == null || response.data() == null)
            throw new BusinessException("RELEASE_MASTERDATA_INVALID", "主数据未返回" + what, HttpStatus.UNPROCESSABLE_ENTITY);
        return response.data();
    }

    /**
     * 补偿释放预留。释放失败不抛出、不覆盖原始异常（作为 suppressed 附加并记日志），由库存 TTL（15 分钟）回收。
     */
    private void compensate(String key, String reservationId, RuntimeException cause) {
        try { materials.release(key, reservationId); }
        catch (RuntimeException e) { cause.addSuppressed(e); LOG.warn("补偿释放预留失败，将由库存 TTL（15 分钟）回收：{}", reservationId, e); }
    }

    // ------------------------------------------------------------------ 冻结 / 恢复

    /**
     * 冻结工单：RELEASED / IN_PROGRESS → ON_HOLD，记录 resume_target；已冻结时追加原因（同一原因重复冻结幂等）。
     */
    public WorkOrderReleaseView hold(String orderId, HoldReason reason, String note, String operator) {
        retrying(() -> { tx.executeWithoutResult(s -> holdInTx(orderId, reason, note, operator)); return null; });
        return view(orderId);
    }

    /** 在调用方事务内冻结；版本冲突抛 VersionConflict 由外层回滚重试。返回是否产生了变更。 */
    private boolean holdInTx(String orderId, HoldReason reason, String note, String operator) {
        Order order = order(orderId);
        Lifecycle lc = requireLifecycle(orderId);
        boolean onHold = "ON_HOLD".equals(order.status());
        if (!onHold && !HOLDABLE.contains(order.status()))
            throw new BusinessException("WORK_ORDER_STATE_CONFLICT", order.status() + " 状态的工单不能冻结", HttpStatus.CONFLICT);
        if (onHold && reasons(orderId).contains(reason.name())) return false; // 幂等：原因已存在
        bump(orderId, lc.version(), onHold ? lc.resumeTarget() : order.status());
        jdbc.update("insert into work_order_hold_reason (order_id, reason) values (?, ?)", orderId, reason.name());
        if (!onHold && jdbc.update("update work_order set status = 'ON_HOLD' where id = ? and status = ?", orderId, order.status()) != 1)
            throw new VersionConflict();
        journal.statusChanged(orderId, order.status(), "ON_HOLD", reason.name(), note, operator);
        journal.event(orderId, "WorkOrderHeld", Map.of("reason", reason.name(), "resumeTarget", onHold ? lc.resumeTarget() : order.status()));
        return true;
    }

    /**
     * 解除一个冻结原因；全部原因解除后恢复到 resume_target。解除 MATERIAL_RESERVATION_EXPIRED 时先向库存重新预留，
     * 预留失败（如短缺 409）则工单保持冻结。对未冻结的工单或不存在的原因幂等返回当前视图。
     */
    public WorkOrderReleaseView resume(String orderId, HoldReason reason, String note, String operator) {
        requireLifecycle(orderId);
        if (!reasons(orderId).contains(reason.name())) return view(orderId);
        MaterialReservationPort.Reservation renewed = null;
        String reservationKey = null;
        int nextSeq = 0;
        if (reason == HoldReason.MATERIAL_RESERVATION_EXPIRED) {
            nextSeq = jdbc.queryForObject("select coalesce(max(seq), 0) + 1 from wo_material_reservation where order_id = ?", Integer.class, orderId);
            // 预留键与新序号绑定：并发 / 重试的恢复请求拿到同一张新预留单。
            reservationKey = "rereserve:" + orderId + ":" + nextSeq;
            renewed = materials.reserve(reservationKey, orderId, snapshotRequirements(orderId));
        }
        MaterialReservationPort.Reservation reservation = renewed;
        int seq = nextSeq;
        try {
            retrying(() -> { tx.executeWithoutResult(s -> resumeInTx(orderId, reason, note, operator, reservation, seq)); return null; });
        } catch (DuplicateKeyException race) {
            // 并发恢复同一 MATERIAL_RESERVATION_EXPIRED 原因：两个请求计算出同一 nextSeq，因而使用同一预留幂等键、拿到同一张
            // 预留单；后提交者在 (order_id, seq) / reservation_id 唯一约束上失败。胜者已删除原因并写入这张预留，
            // 结果与本请求期望一致，因此不释放（释放会撤销胜者的预留），直接返回当前视图。
            // 但只有在原因确实已被解除时才能这样认定；否则（唯一约束冲突另有来源）返回 409 让调用方重试，而不是静默成功。
            // 用例：WorkOrderReleaseIntegrationTest.resumeDuplicateWithoutWinnerIsNotSwallowed。
            if (reasons(orderId).contains(reason.name())) {
                BusinessException conflict = new BusinessException("CONCURRENT_MODIFICATION", "恢复与其他操作冲突，请重试", HttpStatus.CONFLICT);
                // 本次新预留未被任何本地记录引用，补偿释放
                if (reservation != null) compensate(reservationKey, reservation.reservationId(), conflict);
                throw conflict;
            }
            LOG.debug("并发恢复：原因 {} 已由其他请求解除，工单 {}", reason, orderId);
        } catch (RuntimeException e) {
            if (reservation != null) compensate(reservationKey, reservation.reservationId(), e);
            throw e;
        }
        return view(orderId);
    }

    private void resumeInTx(String orderId, HoldReason reason, String note, String operator,
                            MaterialReservationPort.Reservation reservation, int seq) {
        Order order = order(orderId);
        Lifecycle lc = requireLifecycle(orderId);
        if (jdbc.update("delete from work_order_hold_reason where order_id = ? and reason = ?", orderId, reason.name()) == 0) return;
        boolean last = reasons(orderId).isEmpty();
        bump(orderId, lc.version(), last ? null : lc.resumeTarget());
        if (reservation != null) insertReservation(orderId, seq, reservation);
        String to = last ? lc.resumeTarget() : "ON_HOLD";
        if (last && jdbc.update("update work_order set status = ? where id = ? and status = 'ON_HOLD'", to, orderId) != 1)
            throw new VersionConflict();
        journal.statusChanged(orderId, order.status(), to, reason.name(), note, operator);
        journal.event(orderId, last ? "WorkOrderResumed" : "WorkOrderHoldReasonCleared", Map.of("reason", reason.name(), "status", to));
    }

    // ------------------------------------------------------------------ 预留续约与过期

    /** 待续约的预留：工单仍在生命周期内（含 ON_HOLD，质量冻结期间物料仍需保留）。 */
    public record RenewalCandidate(String orderId, String reservationId) {}

    /**
     * 取一批需续约的 ACTIVE 预留：只取本轮开始前未续约过的（last_renewed_at 为空或早于 roundStart），按预留单号游标分页，
     * 续约作业据此循环取批直至取空，保证每轮覆盖全部活动预留。
     */
    public List<RenewalCandidate> renewalCandidates(Instant roundStart, String afterReservationId, int batchSize) {
        return jdbc.query("""
            select r.order_id, r.reservation_id from wo_material_reservation r join work_order w on w.id = r.order_id
            where r.status = 'ACTIVE' and w.status in ('RELEASED', 'IN_PROGRESS', 'ON_HOLD')
              and (r.last_renewed_at is null or r.last_renewed_at < ?) and r.reservation_id > ?
            order by r.reservation_id limit ?""",
            (rs, i) -> new RenewalCandidate(rs.getString(1), rs.getString(2)), Timestamp.from(roundStart), afterReservationId, batchSize);
    }

    /**
     * 续约单张预留，镜像库存返回的新到期时间。库存答复预留已失效时转入过期处理；其他失败留待下一轮重试。
     *
     * @param slot 续约轮次（时间片序号），与预留单号一起组成幂等键，同一轮重复调用不会重复续约
     */
    public boolean renew(RenewalCandidate c, long slot) {
        try {
            Instant expiresAt = materials.renew("wo-renew:" + c.reservationId() + ":" + slot, c.reservationId());
            jdbc.update("update wo_material_reservation set expires_at = ?, last_renewed_at = ? where reservation_id = ? and status = 'ACTIVE'",
                Timestamp.from(expiresAt), Timestamp.from(clock.instant()), c.reservationId());
            return true;
        } catch (MaterialReservationPort.ReservationGoneException gone) {
            onReservationExpired(c.reservationId());
            return false;
        }
    }

    /**
     * 处理库存的 ReservationExpired 事件（或续约时发现的失效）：按预留单号幂等（与库存约定，consumed_event 键为
     * {@code ReservationExpired:{reservationId}}，重复投递与续约发现的失效只处理一次），将预留镜像标为 EXPIRED，
     * 工单以 MATERIAL_RESERVATION_EXPIRED 原因冻结。已完工 / 取消的工单只标记预留，不冻结。
     *
     * @return 是否首次处理该事件
     */
    public boolean onReservationExpired(String reservationId) {
        return retrying(() -> tx.execute(s -> {
            try {
                jdbc.update("insert into consumed_event (event_id, event_type) values (?, 'ReservationExpired')", "ReservationExpired:" + reservationId);
            } catch (DuplicateKeyException duplicate) {
                return false;
            }
            List<String> owner = jdbc.queryForList("select order_id from wo_material_reservation where reservation_id = ? and status = 'ACTIVE'", String.class, reservationId);
            if (owner.isEmpty()) return true; // 未知或已非活动的预留：记录已消费即可
            String orderId = owner.get(0);
            jdbc.update("update wo_material_reservation set status = 'EXPIRED' where reservation_id = ?", reservationId);
            String status = order(orderId).status();
            if (HOLDABLE.contains(status) || "ON_HOLD".equals(status))
                holdInTx(orderId, HoldReason.MATERIAL_RESERVATION_EXPIRED, "库存预留已过期: " + reservationId, "system");
            return true;
        }));
    }

    // ------------------------------------------------------------------ 查询与公共工具

    /** 下达快照视图；未下达的工单返回 404 WORK_ORDER_NOT_RELEASED。 */
    public WorkOrderReleaseView view(String orderId) {
        Order order = order(orderId);
        Lifecycle lc = lifecycle(orderId).orElseThrow(() ->
            new BusinessException("WORK_ORDER_NOT_RELEASED", "工单尚未下达", HttpStatus.NOT_FOUND));
        var head = jdbc.queryForMap("select product_code, bom_version, routing_version, released_at from work_order_lifecycle where order_id = ?", orderId);
        int bomLines = jdbc.queryForObject("select count(*) from wo_bom_snapshot where order_id = ?", Integer.class, orderId);
        List<OperationTaskView> ops = jdbc.query("select op_no, name, work_center_code, status, report_point, first_article_required, inspection_point_codes from operation_task where order_id = ? order by seq",
            (rs, i) -> new OperationTaskView(rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4), rs.getBoolean(5), rs.getBoolean(6),
                rs.getString(7).isEmpty() ? List.of() : List.of(rs.getString(7).split(","))), orderId);
        MaterialReservationView reservation = jdbc.query("select reservation_id, status, expires_at from wo_material_reservation where order_id = ? order by seq desc limit 1",
            (rs, i) -> new MaterialReservationView(rs.getString(1), rs.getString(2), rs.getTimestamp(3).toInstant(), reservationLines(rs.getString(1))), orderId)
            .stream().findFirst().orElse(null);
        return new WorkOrderReleaseView(orderId, order.status(), lc.version(), (String) head.get("product_code"),
            (String) head.get("bom_version"), (String) head.get("routing_version"), ((Timestamp) head.get("released_at")).toInstant(),
            lc.resumeTarget(), reasons(orderId), bomLines, ops, reservation);
    }

    private List<ReservationLineView> reservationLines(String reservationId) {
        return jdbc.query("select line_id, material_code, quantity, uom from wo_material_reservation_line where reservation_id = ? order by material_code",
            (rs, i) -> new ReservationLineView(rs.getString(1), rs.getString(2), rs.getBigDecimal(3), rs.getString(4)), reservationId);
    }

    /** 由冻结的 BOM 快照重新计算预留需求（重新预留不回查主数据，保持下达时的版本）。 */
    private List<ReservationLineView> snapshotRequirements(String orderId) {
        return jdbc.query("select component_code, sum(required_qty), min(uom) from wo_bom_snapshot where order_id = ? and leaf = true group by component_code order by component_code",
            (rs, i) -> new ReservationLineView(null, rs.getString(1), rs.getBigDecimal(2), rs.getString(3)), orderId);
    }

    private void insertReservation(String orderId, int seq, MaterialReservationPort.Reservation r) {
        jdbc.update("update wo_material_reservation set status = 'SUPERSEDED' where order_id = ? and status = 'ACTIVE'", orderId);
        jdbc.update("insert into wo_material_reservation (order_id, seq, reservation_id, status, expires_at) values (?, ?, ?, 'ACTIVE', ?)",
            orderId, seq, r.reservationId(), Timestamp.from(r.expiresAt()));
        for (ReservationLineView l : r.lines())
            jdbc.update("insert into wo_material_reservation_line (reservation_id, line_id, material_code, quantity, uom) values (?, ?, ?, ?, ?)",
                r.reservationId(), l.lineId(), l.materialCode(), l.quantity(), l.uom());
    }

    /** 乐观锁：版本匹配才更新，否则抛 VersionConflict 回滚整笔事务。 */
    private void bump(String orderId, long version, String resumeTarget) {
        if (jdbc.update("update work_order_lifecycle set version = version + 1, resume_target = ? where order_id = ? and version = ?",
            resumeTarget, orderId, version) != 1) throw new VersionConflict();
    }

    private <T> T retrying(Supplier<T> work) {
        for (int attempt = 1; ; attempt++) {
            try { return work.get(); }
            catch (VersionConflict conflict) {
                if (attempt >= MAX_ATTEMPTS)
                    throw new BusinessException("CONCURRENT_MODIFICATION", "工单正被并发修改，请稍后重试", HttpStatus.CONFLICT);
            }
        }
    }

    private Order order(String id) {
        return jdbc.query("select id, product, quantity, status from work_order where id = ?",
                (rs, i) -> new Order(rs.getString(1), rs.getString(2), rs.getInt(3), rs.getString(4)), id)
            .stream().findFirst().orElseThrow(() -> new BusinessException("WORK_ORDER_NOT_FOUND", "工单不存在", HttpStatus.NOT_FOUND));
    }

    private Optional<Lifecycle> lifecycle(String id) {
        return jdbc.query("select version, resume_target, release_key from work_order_lifecycle where order_id = ?",
            (rs, i) -> new Lifecycle(rs.getLong(1), rs.getString(2), rs.getString(3)), id).stream().findFirst();
    }

    private Lifecycle requireLifecycle(String id) {
        order(id);
        return lifecycle(id).orElseThrow(() -> new BusinessException("WORK_ORDER_STATE_CONFLICT", "工单尚未下达", HttpStatus.CONFLICT));
    }

    private List<String> reasons(String orderId) {
        return jdbc.queryForList("select reason from work_order_hold_reason where order_id = ? order by reason", String.class, orderId);
    }
}
