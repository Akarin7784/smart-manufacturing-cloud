package com.smartmanufacturing.workorder;
import com.smartmanufacturing.api.*;
import com.smartmanufacturing.api.workorder.WorkOrderReferenceView;
import com.smartmanufacturing.common.BusinessException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.*;

/**
 * 工单应用服务：幂等创建、设备校验与工单持久化，只写本服务数据库。
 * <p>
 * 幂等记录保留期为 7 天，由 {@link IdempotencyRecordCleaner} 定时清理；过期后同一键视为新请求。
 * <p>
 * 事务边界说明：远程设备校验与预留在事务<b>之外</b>执行，避免远程调用期间占用数据库连接与锁；
 * 工单与幂等记录在同一本地事务内写入，二者要么同时存在要么都不存在。不使用分布式事务。
 */
@Service
public class WorkOrderService {
    private final DeviceClient devices;
    private final DeviceReservationPort reservations;
    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;

    public WorkOrderService(DeviceClient devices, DeviceReservationPort reservations, JdbcTemplate jdbc, TransactionTemplate tx) {
        this.devices = devices; this.reservations = reservations; this.jdbc = jdbc; this.tx = tx;
    }

    /**
     * 创建结果：{@code replayed=true} 表示命中幂等记录，返回的是首次创建的工单，未产生新写入。
     */
    public record CreateResult(WorkOrder order, boolean replayed) {}

    /**
     * 幂等创建工单。
     *
     * @param principal      调用方身份（JWT subject），幂等键按调用方隔离，防止不同用户的键相互命中
     * @param idempotencyKey 客户端生成的幂等键，同一业务请求的所有重试必须使用同一个键
     * @throws BusinessException 同键不同请求体返回 422；设备离线 409；设备状态无法确认 503
     */
    public CreateResult create(String principal, String idempotencyKey, CreateWorkOrder raw) {
        // 先规范化再计算摘要与落库，保证“轴承”与“轴承 ”这类等价输入命中同一幂等记录。
        CreateWorkOrder request = normalize(raw);
        String hash = hash(request);
        // 快速路径：重试请求直接返回首次结果，不再调用设备服务。
        Optional<CreateResult> replay = findReplay(principal, idempotencyKey, hash);
        if (replay.isPresent()) return replay.get();

        ensureDeviceOnline(request.deviceId());
        String orderId = UUID.randomUUID().toString();
        // 预留键与幂等键绑定，使重试时对 device-service 的预留调用同样幂等。
        String reservationKey = principal + ":" + idempotencyKey;
        reservations.reserve(reservationKey, request.deviceId());
        WorkOrder order = new WorkOrder(orderId, request.deviceId(), request.product(), request.quantity(), "CREATED");
        try {
            tx.executeWithoutResult(status -> {
                // 先写幂等记录：并发相同键时由主键唯一约束裁决，失败方整笔回滚，不会留下孤立工单。
                jdbc.update("insert into work_order_request (principal, idempotency_key, request_hash, order_id) values (?, ?, ?, ?)",
                    principal, idempotencyKey, hash, orderId);
                // 参数绑定避免业务输入被拼接进 SQL。
                jdbc.update("insert into work_order (id, device_id, product, quantity, status) values (?, ?, ?, ?, ?)",
                    order.id(), order.deviceId(), order.product(), order.quantity(), order.status());
            });
            return new CreateResult(order, false);
        } catch (DuplicateKeyException race) {
            // 并发竞争失败：另一请求已用同键提交。预留与胜者共用同一预留键，此处不释放，以免撤销胜者的占用。
            // 理论上只有胜者记录恰好被保留期清理时才会读不到，此时提示客户端重试而不是返回 500。
            return findReplay(principal, idempotencyKey, hash).orElseThrow(() ->
                new BusinessException("IDEMPOTENCY_CONFLICT", "幂等键正在被并发使用，请稍后重试", HttpStatus.CONFLICT));
        } catch (RuntimeException e) {
            // 本地事务失败：补偿释放预留；补偿本身失败时由预留 TTL 兜底，不掩盖原始异常。
            try { reservations.release(reservationKey, request.deviceId()); } catch (RuntimeException ignored) { e.addSuppressed(ignored); }
            throw e;
        }
    }

    /** 查询幂等记录；摘要不一致说明客户端复用了键却改了请求体，按 422 拒绝而非静默返回旧结果。 */
    private Optional<CreateResult> findReplay(String principal, String key, String hash) {
        List<String[]> rows = jdbc.query("select request_hash, order_id from work_order_request where principal = ? and idempotency_key = ?",
            (rs, n) -> new String[] { rs.getString(1), rs.getString(2) }, principal, key);
        if (rows.isEmpty()) return Optional.empty();
        if (!rows.get(0)[0].equals(hash))
            throw new BusinessException("IDEMPOTENCY_KEY_REUSED", "幂等键已用于不同的请求内容", HttpStatus.UNPROCESSABLE_ENTITY);
        WorkOrder existing = jdbc.queryForObject("select id, device_id, product, quantity, status from work_order where id = ?",
            (rs, n) -> new WorkOrder(rs.getString(1), rs.getString(2), rs.getString(3), rs.getInt(4), rs.getString(5)), rows.get(0)[1]);
        return Optional.of(new CreateResult(existing, true));
    }

    /** 设备状态校验：无法确认时拒绝创建，降级不伪造在线状态。 */
    private void ensureDeviceOnline(String deviceId) {
        ApiResponse<Device> response = devices.get(deviceId);
        if (response == null || !"OK".equals(response.code()) || response.data() == null)
            throw new BusinessException("DEVICE_SERVICE_UNAVAILABLE", "无法确认设备状态，工单未创建", HttpStatus.SERVICE_UNAVAILABLE);
        if (!"ONLINE".equals(response.data().status()))
            throw new BusinessException("DEVICE_OFFLINE", "设备离线，无法创建工单", HttpStatus.CONFLICT);
    }

    /**
     * 请求规范化：product 去除首尾空白并做 Unicode NFC 规范化（全角/组合字符等价形式统一）；
     * deviceId 只去除首尾空白，<b>区分大小写</b>，与设备服务主键的匹配规则一致。
     */
    static CreateWorkOrder normalize(CreateWorkOrder r) {
        return new CreateWorkOrder(r.deviceId().strip(), java.text.Normalizer.normalize(r.product().strip(), java.text.Normalizer.Form.NFC), r.quantity());
    }

    /** 请求摘要：对规范化后的字段以不可见分隔符拼接再做 SHA-256，用于识别“同键不同内容”。 */
    static String hash(CreateWorkOrder r) {
        try {
            byte[] d = MessageDigest.getInstance("SHA-256")
                .digest((r.deviceId() + '\u001f' + r.product() + '\u001f' + r.quantity()).getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(d);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("JVM 缺少 SHA-256", e);
        }
    }

    /** 工单状态全集：列表按状态过滤时校验取值。 */
    static final Set<String> STATUSES = Set.of("CREATED", "RELEASED", "IN_PROGRESS", "ON_HOLD", "COMPLETED", "CLOSED", "CANCELLED");
    /** 单页上限。 */
    static final int MAX_PAGE_SIZE = 100;

    /**
     * 分页查询工单（按工单号排序）：status 精确匹配；keyword 对工单号与产品做包含匹配（% 与 _ 按字面量处理；转义符用 !，避免 MySQL 反斜杠转义差异）。
     * page 从 0 开始，size 1–100；参数非法返回 400 validation-error。
     */
    public PageView<WorkOrder> list(int page, int size, String status, String keyword) {
        if (page < 0 || size < 1 || size > MAX_PAGE_SIZE)
            throw WorkOrderProblemType.VALIDATION_ERROR.exception("page 必须 ≥ 0，size 必须在 1–" + MAX_PAGE_SIZE + " 之间");
        StringBuilder where = new StringBuilder(" where 1 = 1");
        List<Object> args = new ArrayList<>();
        if (status != null && !status.isBlank()) {
            if (!STATUSES.contains(status))
                throw WorkOrderProblemType.VALIDATION_ERROR.exception("未知的工单状态：" + status);
            where.append(" and status = ?");
            args.add(status);
        }
        if (keyword != null && !keyword.isBlank()) {
            String like = "%" + keyword.strip().replace("!", "!!").replace("%", "!%").replace("_", "!_") + "%";
            where.append(" and (id like ? escape '!' or product like ? escape '!')");
            args.add(like);
            args.add(like);
        }
        Long total = jdbc.queryForObject("select count(*) from work_order" + where, Long.class, args.toArray());
        List<WorkOrder> items = jdbc.query("select id, device_id, product, quantity, status from work_order" + where
                + " order by id limit " + size + " offset " + ((long) page * size),
            (rs, row) -> new WorkOrder(rs.getString(1), rs.getString(2), rs.getString(3), rs.getInt(4), rs.getString(5)), args.toArray());
        return new PageView<>(items, page, size, total == null ? 0 : total);
    }

    /**
     * 主数据引用检查（主数据停用工作中心 / 工位前调用）：统计仍在执行中的工单（未完工、未关闭、未取消）
     * 中引用该工作中心或工位的工单数，最多返回前 10 个工单号。
     */
    public WorkOrderReferenceView references(String workCenter, String station) {
        if ((workCenter == null || workCenter.isBlank()) == (station == null || station.isBlank()))
            throw WorkOrderProblemType.VALIDATION_ERROR.exception("workCenter 与 station 必须且只能提供一个");
        String column = station != null && !station.isBlank() ? "t.station_code" : "t.work_center_code";
        String code = station != null && !station.isBlank() ? station : workCenter;
        List<String> orders = jdbc.queryForList("select distinct t.order_id from operation_task t join work_order w on w.id = t.order_id"
            + " where " + column + " = ? and w.status not in ('COMPLETED', 'CLOSED', 'CANCELLED') order by t.order_id", String.class, code);
        return new WorkOrderReferenceView(orders.size(), orders.subList(0, Math.min(10, orders.size())));
    }
}
