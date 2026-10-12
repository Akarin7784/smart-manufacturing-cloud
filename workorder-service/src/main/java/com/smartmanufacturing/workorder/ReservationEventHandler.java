package com.smartmanufacturing.workorder;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;
import java.util.List;
import java.util.Map;

/**
 * 库存预留事件（topic mes-inventory-reservation，tag ReservationCreated / Released / Consumed / Expired）的处理逻辑，
 * 与传输无关（RocketMQ 消费者见 {@link ReservationEventConsumer}），便于测试直接驱动。
 * <ul>
 *   <li>按 eventId 幂等：consumed_event 主键去重，重复投递只处理一次。</li>
 *   <li>按 aggregateVersion 排序：版本按预留单单调递增但<b>不连续</b>，不能用“本地版本 + 1”判断，
 *       只丢弃 {@code aggregateVersion <= 本地已处理版本} 的事件，其余处理后把本地版本推进到该值。</li>
 *   <li>Created：只推进版本（预留镜像在下达事务中已写入）；Released / Consumed：ACTIVE 或 RELEASING 的镜像转为对应终态；
 *       Expired：交给 {@link WorkOrderReleaseService#onReservationExpired}（镜像置 EXPIRED，进行中的工单以 MATERIAL_RESERVATION_EXPIRED 冻结）。</li>
 *   <li>未知预留单（非本服务创建或已清理）：记为已消费后忽略。格式非法：记 invalid 并丢弃（重投也无法处理）。</li>
 * </ul>
 * 续约不发事件，到期时间以续约响应为准，事件不会覆盖本地 expires_at。
 */
@Component
class ReservationEventHandler {
    private static final Logger LOG = LoggerFactory.getLogger(ReservationEventHandler.class);
    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;
    private final ObjectMapper mapper;
    private final WorkOrderReleaseService releases;
    private final InventoryClientMetrics metrics;
    /** tag → 事件类型（tag 值取自 Nacos manufacturing.mq.tags，事件类型以契约为准）。 */
    private final Map<String, String> types;

    ReservationEventHandler(JdbcTemplate jdbc, TransactionTemplate tx, ObjectMapper mapper, WorkOrderReleaseService releases,
                            InventoryClientMetrics metrics, MqProperties mq) {
        this.jdbc = jdbc; this.tx = tx; this.mapper = mapper; this.releases = releases; this.metrics = metrics;
        this.types = Map.of(mq.tag("reservation-created"), "ReservationCreated", mq.tag("reservation-released"), "ReservationReleased",
            mq.tag("reservation-consumed"), "ReservationConsumed", mq.tag("reservation-expired"), "ReservationExpired");
    }

    /** 订阅表达式中的 tag 列表。 */
    java.util.Set<String> tags() { return types.keySet(); }

    /** 处理结果，与指标 mes_workorder_reservation_event_total 的 result 标签一致。 */
    String handle(String tag, String body) {
        String result;
        try {
            JsonNode e = mapper.readTree(body);
            String type = types.get(tag);
            String eventId = e.path("eventId").asText(null), reservationId = e.path("reservationId").asText(null);
            if (type == null || !type.equals(e.path("eventType").asText()) || eventId == null || reservationId == null || !e.path("aggregateVersion").canConvertToLong())
                result = "invalid";
            else {
                result = tx.execute(s -> apply(eventId, type, reservationId, e.path("aggregateVersion").asLong()));
                // 过期处理在独立事务中执行（自带乐观锁重试），不嵌套在本事务里：嵌套时内层版本冲突会把外层标记为
                // rollback-only，提交时抛 UnexpectedRollbackException。onReservationExpired 自身按
                // ReservationExpired:{reservationId} 幂等，所以重复投递（duplicate）时也再调用一次：若首次在去重提交后、
                // 过期处理前失败，重投时仍能补上冻结。过时事件（stale）不处理。
                if ("ReservationExpired".equals(type) && ("applied".equals(result) || "duplicate".equals(result)))
                    releases.onReservationExpired(reservationId);
            }
        } catch (java.io.IOException ex) {
            result = "invalid";
        }
        if ("invalid".equals(result)) LOG.warn("丢弃无法处理的库存预留事件 tag={} body={}", tag, body.length() > 300 ? body.substring(0, 300) : body);
        metrics.eventResult(result);
        return result;
    }

    private String apply(String eventId, String type, String reservationId, long version) {
        try {
            jdbc.update("insert into consumed_event (event_id, event_type) values (?, ?)", eventId, type);
        } catch (DuplicateKeyException duplicate) {
            return "duplicate";
        }
        // 锁住镜像行：同一预留单的事件串行处理，版本比较与推进原子完成
        List<Long> local = jdbc.query("select aggregate_version from wo_material_reservation where reservation_id = ? for update",
            (rs, i) -> rs.getObject(1) == null ? -1L : rs.getLong(1), reservationId);
        if (local.isEmpty()) return "unknown_reservation";
        if (version <= local.get(0)) {
            LOG.debug("丢弃过时事件 {}：预留 {} 版本 {} ≤ 本地 {}", type, reservationId, version, local.get(0));
            return "stale";
        }
        jdbc.update("update wo_material_reservation set aggregate_version = ? where reservation_id = ?", version, reservationId);
        switch (type) {
            case "ReservationReleased" -> terminal(reservationId, "RELEASED");
            case "ReservationConsumed" -> terminal(reservationId, "CONSUMED");
            case "ReservationExpired" -> {
                // 已取消（RELEASING）的预留不冻结工单，只更新镜像；释放任务随后会得到 200 EXPIRED 并完成
                terminal(reservationId, "EXPIRED", "RELEASING"); // ACTIVE 的镜像由事务外的 onReservationExpired 处理
            }
            default -> { } // ReservationCreated：镜像已在下达事务中写入
        }
        return "applied";
    }

    private void terminal(String reservationId, String to, String... from) {
        String states = from.length == 0 ? "'ACTIVE', 'RELEASING'" : "'" + String.join("', '", from) + "'";
        jdbc.update("update wo_material_reservation set status = ? where reservation_id = ? and status in (" + states + ")", to, reservationId);
    }
}
