package com.smartmanufacturing.events;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.smartmanufacturing.api.WorkOrderCreatedEvent;
import java.time.Instant;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import static org.junit.jupiter.api.Assertions.*;
/** 验证真实数据库事务下的消息去重、冲突拒绝与投影回滚。 */
@SpringBootTest
@org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
@ActiveProfiles("test")
class EventInboxTest {
    @Autowired EventInboxService inbox;
    @Autowired ObjectMapper mapper;
    @Autowired JdbcTemplate jdbc;
    @Autowired org.springframework.test.web.servlet.MockMvc mvc;
    @MockitoBean JwtDecoder decoder;
    /** 清理幂等记录和业务投影，保证测试之间互不影响。 */
    @BeforeEach void clear() { jdbc.update("delete from work_order_event"); jdbc.update("delete from processed_event"); }
    private WorkOrderCreatedEvent event(String id, int quantity) {
        return new WorkOrderCreatedEvent(id,1,"WorkOrderCreated","order-1","D001","bearing",quantity,Instant.parse("2026-10-12T00:00:00Z"),"request-1");
    }
    /** 相同事件重复投递仅产生一个投影；重投使用同一事件 ID。 */
    @Test void duplicateDeliveryIsIdempotent() throws Exception {
        byte[] body=mapper.writeValueAsBytes(event("event-1",10));
        inbox.accept(body); inbox.accept(body);
        assertEquals(1,jdbc.queryForObject("select count(*) from work_order_event",Integer.class));
    }
    /** 相同 ID 对应不同内容必须失败，不能误当作成功去重。 */
    @Test void conflictingDuplicateIsRejected() throws Exception {
        inbox.accept(mapper.writeValueAsBytes(event("event-1",10)));
        assertThrows(Exception.class,()->inbox.accept(mapper.writeValueAsBytes(event("event-1",20))));
        assertEquals(10,jdbc.queryForObject("select quantity from work_order_event",Integer.class));
    }
    /** 业务投影写入失败时幂等标记也回滚，后续消息仍可以重试。 */
    @Test void projectionFailureRollsBackInbox() throws Exception {
        jdbc.update("insert into work_order_event values ('event-1','other','D001','bearing',1,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)");
        assertThrows(Exception.class,()->inbox.accept(mapper.writeValueAsBytes(event("event-1",10))));
        assertEquals(0,jdbc.queryForObject("select count(*) from processed_event",Integer.class));
    }
    /** 非法版本和非法数量在数据库写入之前拒绝。 */
    @Test void invalidEventIsRejected() throws Exception {
        assertThrows(IllegalArgumentException.class,()->inbox.accept(mapper.writeValueAsBytes(event("event-1",0))));
        assertEquals(0,jdbc.queryForObject("select count(*) from processed_event",Integer.class));
    }
    /** 内部消费服务也必须要求身份，不能匿名读取事件投影。 */
    @Test void anonymousProjectionQueryIsRejected() throws Exception {
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/events/workorders"))
            .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isUnauthorized());
    }
    /** 缺少工单查询权限的身份不能读取事件业务内容。 */
    @Test void unrelatedPermissionCannotReadProjection() throws Exception {
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/events/workorders")
            .with(org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt()
                .authorities(new org.springframework.security.core.authority.SimpleGrantedAuthority("device:read"))))
            .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isForbidden());
    }
}
