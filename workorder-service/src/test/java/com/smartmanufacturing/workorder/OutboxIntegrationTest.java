package com.smartmanufacturing.workorder;
import com.smartmanufacturing.api.*;
import com.smartmanufacturing.messaging.RocketMqProperties;
import com.smartmanufacturing.workorder.messaging.*;
import java.sql.Timestamp;
import java.time.Instant;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
/** 验证工单与事件原子提交、租约恢复以及发送失败的持久化重试。 */
@SpringBootTest
@ActiveProfiles("test")
class OutboxIntegrationTest {
    @Autowired JdbcTemplate jdbc;
    @Autowired WorkOrderService orders;
    @Autowired OutboxStore store;
    @Autowired RocketMqProperties properties;
    @MockitoBean DeviceClient devices;
    @MockitoBean JwtDecoder decoder;
    /** 每个用例清理消息及工单数据，恢复配置以免重试上限互相影响。 */
    @BeforeEach void clear() {
        jdbc.update("delete from outbox_event"); jdbc.update("delete from work_order");
        properties.setMaxAttempts(8); properties.setLeaseSeconds(60);
        when(devices.get("D001")).thenReturn(ApiResponse.ok(new Device("D001","CNC","ONLINE")));
    }
    private String create() {
        orders.create(new CreateWorkOrder("D001","bearing",10));
        return jdbc.queryForObject("select event_id from outbox_event",String.class);
    }
    /** 同库事件写入失败时必须回滚已插入工单，不能留下无事件的业务记录。 */
    @Test void eventInsertFailureRollsBackOrder() {
        jdbc.execute("alter table outbox_event add constraint reject_pending check (status <> 'PENDING')");
        try { assertThrows(Exception.class,()->orders.create(new CreateWorkOrder("D001","bearing",10)));
            assertEquals(0,jdbc.queryForObject("select count(*) from work_order",Integer.class));
        } finally { jdbc.execute("alter table outbox_event drop constraint reject_pending"); }
    }
    /** 租约过期后可以接管，旧实例确认不得覆盖新实例所有权。 */
    @Test void leaseRecoveryRejectsStaleAcknowledgement() {
        String id=create(); Instant now=Instant.now().plusSeconds(1);
        var first=store.claim(id,now).orElseThrow();
        assertTrue(store.claim(id,now).isEmpty());
        var second=store.claim(id,now.plusSeconds(61)).orElseThrow();
        store.sent(first,now.plusSeconds(62));
        assertEquals("PROCESSING",jdbc.queryForObject("select status from outbox_event",String.class));
        store.sent(second,now.plusSeconds(63));
        assertEquals("SENT",jdbc.queryForObject("select status from outbox_event",String.class));
    }
    /** Broker 不可用时保留事件和工单，延迟到期后可以重新发送。 */
    @Test void failedSendIsRetriedWithoutLosingOrder() {
        create();
        new OutboxDispatcher(store,(id,body)->{throw new java.io.IOException("broker unavailable");}).dispatch();
        assertEquals("PENDING",jdbc.queryForObject("select status from outbox_event",String.class));
        assertEquals(1,jdbc.queryForObject("select attempts from outbox_event",Integer.class));
        assertEquals(1,jdbc.queryForObject("select count(*) from work_order",Integer.class));
        jdbc.update("update outbox_event set available_at=?",Timestamp.from(Instant.now().minusSeconds(1)));
        new OutboxDispatcher(store,(id,body)->{}).dispatch();
        assertEquals("SENT",jdbc.queryForObject("select status from outbox_event",String.class));
    }
    /** 达到上限进入 FAILED，管理员重试保持原事件 ID 不变。 */
    @Test void exhaustedRetriesRequireManualRecovery() {
        properties.setMaxAttempts(1); String id=create();
        new OutboxDispatcher(store,(event,body)->{throw new java.io.IOException("offline");}).dispatch();
        assertEquals("FAILED",jdbc.queryForObject("select status from outbox_event",String.class));
        assertTrue(store.retry(id));
        assertFalse(store.retry(id));
        assertEquals(id,jdbc.queryForObject("select event_id from outbox_event",String.class));
    }
}
