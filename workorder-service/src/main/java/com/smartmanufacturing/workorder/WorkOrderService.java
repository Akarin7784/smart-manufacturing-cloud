package com.smartmanufacturing.workorder;
import com.smartmanufacturing.api.*;
import com.smartmanufacturing.common.BusinessException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import java.util.*;
/** 执行设备状态校验和工单持久化，只写本服务数据库。 */
@Service
public class WorkOrderService {
    private final DeviceClient devices;
    private final JdbcTemplate jdbc;
    private final org.springframework.transaction.support.TransactionTemplate transactions;
    private final com.fasterxml.jackson.databind.ObjectMapper mapper;
    public WorkOrderService(DeviceClient devices, JdbcTemplate jdbc,
            org.springframework.transaction.support.TransactionTemplate transactions, com.fasterxml.jackson.databind.ObjectMapper mapper) {
        this.devices = devices; this.jdbc = jdbc; this.transactions = transactions; this.mapper = mapper;
    }
    /** 先确认设备在线再写入工单，远程校验失败时不产生本地写入。 */
    public WorkOrder create(CreateWorkOrder request) {
        // 查询不预留设备资源；状态读取与工单写入不具备跨服务原子性。
        ApiResponse<Device> response = devices.get(request.deviceId());
        if (response == null || !"OK".equals(response.code()) || response.data() == null)
            throw new BusinessException("DEVICE_SERVICE_UNAVAILABLE", "无法确认设备状态，工单未创建", HttpStatus.SERVICE_UNAVAILABLE);
        if (!"ONLINE".equals(response.data().status()))
            throw new BusinessException("DEVICE_OFFLINE", "设备离线，无法创建工单", HttpStatus.CONFLICT);
        WorkOrder order = new WorkOrder(UUID.randomUUID().toString(), request.deviceId(), request.product(), request.quantity(), "CREATED");
        java.time.Instant now = java.time.Instant.now();
        WorkOrderCreatedEvent event = new WorkOrderCreatedEvent(UUID.randomUUID().toString(), WorkOrderCreatedEvent.VERSION,
            WorkOrderCreatedEvent.TYPE, order.id(), order.deviceId(), order.product(), order.quantity(), now, org.slf4j.MDC.get("requestId"));
        final String payload;
        try { payload = mapper.writeValueAsString(event); }
        catch (com.fasterxml.jackson.core.JsonProcessingException e) { throw new IllegalStateException("工单事件序列化失败", e); }
        // 工单和事件在同一数据库事务中提交；请求线程不发送 MQ，Broker 故障不会丢失已提交事件。
        return transactions.execute(status -> {
            jdbc.update("insert into work_order (id, device_id, product, quantity, status) values (?, ?, ?, ?, ?)",
                order.id(), order.deviceId(), order.product(), order.quantity(), order.status());
            jdbc.update("insert into outbox_event (event_id, order_id, event_type, payload, status, attempts, available_at, created_at) "
                + "values (?, ?, ?, ?, 'PENDING', 0, ?, ?)", event.eventId(), order.id(), event.eventType(),
                payload, java.sql.Timestamp.from(now), java.sql.Timestamp.from(now));
            return order;
        });
    }
    /** 从工单库读取数据，不直接访问其他服务数据库。 */
    public List<WorkOrder> list() {
        return jdbc.query("select id, device_id, product, quantity, status from work_order order by id",
            (rs, row) -> new WorkOrder(rs.getString(1), rs.getString(2), rs.getString(3), rs.getInt(4), rs.getString(5)));
    }
}
