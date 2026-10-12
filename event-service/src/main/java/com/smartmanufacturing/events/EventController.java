package com.smartmanufacturing.events;

import com.smartmanufacturing.api.ApiResponse;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

/** 查询已成功处理的工单事件；不会把消息基础设施权限授予普通业务用户。 */
@RestController
@RequestMapping("/events/workorders")
@PreAuthorize("hasAuthority('workorder:read')")
public class EventController {
    private final JdbcTemplate jdbc;
    public EventController(JdbcTemplate jdbc) { this.jdbc = jdbc; }
    /** 限制返回最近一百条，后续可按业务需求扩展游标分页。 */
    @GetMapping public ApiResponse<List<ReceivedWorkOrder>> list() {
        return ApiResponse.ok(jdbc.query("select event_id, order_id, device_id, product, quantity, occurred_at, received_at "
            + "from work_order_event order by received_at desc, event_id limit 100",
            (rs, row) -> new ReceivedWorkOrder(rs.getString(1), rs.getString(2), rs.getString(3),
                rs.getString(4), rs.getInt(5), rs.getTimestamp(6).toInstant(), rs.getTimestamp(7).toInstant())));
    }
    /** 消费投影响应，不包含原始消息头、数据库错误或任何身份令牌。 */
    public record ReceivedWorkOrder(String eventId, String orderId, String deviceId, String product,
                                    int quantity, Instant occurredAt, Instant receivedAt) {}
}
