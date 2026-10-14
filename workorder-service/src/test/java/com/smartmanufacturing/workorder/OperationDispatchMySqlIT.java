package com.smartmanufacturing.workorder;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * 派工并发门槛（真实 MySQL 8.4，经 HTTP 入口；需要 Docker，只在 CI 运行）：
 * 两个计划员拿同一个 expectedVersion 并发改派，只有一个成功，另一个 409 concurrent-modification；
 * 并发开工同一任务也只有一个成功，另一个 409 task-state-conflict。串行化依赖“锁工单行 + 任务 version 条件更新”。
 */
@Testcontainers
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class OperationDispatchMySqlIT {
    @Container @ServiceConnection
    static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.4");

    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired WorkOrderReleaseService release;
    @Autowired LocalMaterialReservation stub;
    @MockitoBean MasterdataClient masterdata;
    @MockitoBean DeviceClient devices;
    @MockitoBean org.springframework.security.oauth2.jwt.JwtDecoder decoder;

    @BeforeEach void setUp() { ReleaseFixtures.clear(jdbc); ReleaseFixtures.stubMasterdata(masterdata); stub.reset(); }

    private MvcResult call(String subject, String path, String body) throws Exception {
        return mvc.perform(post(path).with(jwt().jwt(j -> j.subject(subject)).authorities(new SimpleGrantedAuthority("workorder:read"),
                new SimpleGrantedAuthority("execution:dispatch"), new SimpleGrantedAuthority("execution:report")))
            .contentType(MediaType.APPLICATION_JSON).header("Idempotency-Key", UUID.randomUUID().toString()).content(body)).andReturn();
    }

    private String dispatched() throws Exception {
        String id = ReleaseFixtures.createOrder(jdbc, 10);
        release.release(id, "rel-" + id, "planner");
        assertThat(call("p0", "/workorders/" + id + "/operations/OP10/dispatch", "{\"stationCode\":\"ST-CNC-01\"}").getResponse().getStatus()).isEqualTo(200);
        return id;
    }

    /** 并发执行两个请求，返回各自的状态码与响应体。 */
    private List<MvcResult> race(Callable<MvcResult> a, Callable<MvcResult> b) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            CountDownLatch go = new CountDownLatch(1);
            Future<MvcResult> fa = pool.submit(() -> { go.await(); return a.call(); });
            Future<MvcResult> fb = pool.submit(() -> { go.await(); return b.call(); });
            go.countDown();
            return List.of(fa.get(30, TimeUnit.SECONDS), fb.get(30, TimeUnit.SECONDS));
        } finally { pool.shutdownNow(); }
    }

    @Test void concurrentReassignOnlyOneWins() throws Exception {
        for (int round = 0; round < 5; round++) {
            String id = dispatched();
            long v = jdbc.queryForObject("select version from operation_task where order_id = ? and op_no = 'OP10'", Long.class, id);
            String path = "/workorders/" + id + "/operations/OP10/reassign";
            var results = race(() -> call("p1", path, "{\"stationCode\":\"ST-CNC-02\",\"operator\":\"op-a\",\"expectedVersion\":" + v + "}"),
                               () -> call("p2", path, "{\"stationCode\":\"ST-CNC-01\",\"operator\":\"op-b\",\"expectedVersion\":" + v + "}"));
            List<Integer> codes = results.stream().map(r -> r.getResponse().getStatus()).sorted().toList();
            assertThat(codes).containsExactly(200, 409);
            MvcResult loser = results.stream().filter(r -> r.getResponse().getStatus() == 409).findFirst().orElseThrow();
            assertThat(loser.getResponse().getContentAsString(StandardCharsets.UTF_8)).contains("concurrent-modification");
            // 任务版本只前进一次，日志只有一条 REASSIGN
            assertThat(jdbc.queryForObject("select version from operation_task where order_id = ? and op_no = 'OP10'", Long.class, id)).isEqualTo(v + 1);
            assertThat(jdbc.queryForObject("select count(*) from operation_task_log where order_id = ? and action = 'REASSIGN'", Integer.class, id)).isEqualTo(1);
        }
    }

    @Test void concurrentStartOnlyOneWins() throws Exception {
        String id = dispatched();
        String path = "/workorders/" + id + "/operations/OP10/start";
        var results = race(() -> call("op-a", path, "{}"), () -> call("op-b", path, "{}"));
        assertThat(results.stream().map(r -> r.getResponse().getStatus()).sorted().toList()).containsExactly(200, 409);
        assertThat(jdbc.queryForObject("select count(*) from operation_task_log where order_id = ? and action = 'START'", Integer.class, id)).isEqualTo(1);
    }
}
