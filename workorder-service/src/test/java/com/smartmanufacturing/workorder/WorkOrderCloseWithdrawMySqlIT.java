package com.smartmanufacturing.workorder;

import com.smartmanufacturing.api.workorder.HoldReason;
import com.smartmanufacturing.api.workorder.WithdrawReleaseRequest;
import com.smartmanufacturing.common.BusinessException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.*;
import java.util.concurrent.*;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * M1 PR2 在真实 MySQL 8.4 上的 SQL 语义（需要 Docker，只在 CI 运行）：列表关键字的 LIKE 转义（escape '!'，与 MySQL 反斜杠转义无关）、
 * 主数据引用查询、撤回下达后重新下达（预留序号顺延），以及撤回下达与冻结并发时不死锁（锁序：生命周期行 → 工单行）。
 */
@Testcontainers
@SpringBootTest
@ActiveProfiles("test")
class WorkOrderCloseWithdrawMySqlIT {
    @Container @ServiceConnection
    static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.4");

    @Autowired JdbcTemplate jdbc;
    @Autowired WorkOrderService orders;
    @Autowired WorkOrderReleaseService release;
    @Autowired OperationReportingService reporting;
    @Autowired LocalMaterialReservation stub;
    @MockitoBean MasterdataClient masterdata;
    @MockitoBean DeviceClient devices;
    @MockitoBean QualityClient quality;
    @MockitoBean org.springframework.security.oauth2.jwt.JwtDecoder decoder;

    @BeforeEach void setUp() { ReleaseFixtures.clear(jdbc); ReleaseFixtures.stubMasterdata(masterdata); stub.reset(); }

    private String released() {
        String id = ReleaseFixtures.createOrder(jdbc, 5);
        release.release(id, "rel-" + id, "planner");
        return id;
    }

    /** 关键字中的 % _ ! 按字面量匹配；状态过滤与分页总数正确。 */
    @Test void listKeywordEscapesOnMySql() {
        String a = ReleaseFixtures.createOrder(jdbc, 1);
        String b = ReleaseFixtures.createOrder(jdbc, 1);
        jdbc.update("update work_order set product = 'A_50%!x' where id = ?", a);
        jdbc.update("update work_order set product = 'AB50Zx' where id = ?", b);
        assertThat(orders.list(0, 20, null, "_50%!").items()).extracting(com.smartmanufacturing.api.WorkOrder::id).containsExactly(a);
        assertThat(orders.list(0, 20, null, "50").total()).isEqualTo(2);
        assertThat(orders.list(0, 1, "CREATED", null).items()).hasSize(1);
        assertThat(orders.list(0, 1, "CREATED", null).total()).isEqualTo(2);
    }

    /** 引用查询只统计执行中的工单。 */
    @Test void referencesOnMySql() {
        String id = released();
        assertThat(orders.references("WC-CNC", null).count()).isEqualTo(1);
        jdbc.update("update work_order set status = 'CLOSED' where id = ?", id);
        assertThat(orders.references("WC-CNC", null).count()).isZero();
    }

    /** 撤回后换键重新下达：生命周期主键可再次插入，预留序号顺延为 2。 */
    @Test void withdrawThenReReleaseOnMySql() {
        String id = released();
        reporting.withdrawRelease(id, new WithdrawReleaseRequest(null), "wd-" + id, "planner");
        assertThat(jdbc.queryForObject("select status from work_order where id = ?", String.class, id)).isEqualTo("CREATED");
        release.release(id, "rel2-" + id, "planner");
        assertThat(jdbc.queryForObject("select max(seq) from wo_material_reservation where order_id = ?", Integer.class, id)).isEqualTo(2);
    }

    /** 撤回下达与冻结并发：只能是一方成功或双方按业务规则失败，不得出现死锁 / 未映射异常；最终状态一致。 */
    @Test void concurrentWithdrawAndHoldDoNotDeadlock() throws Exception {
        for (int round = 0; round < 5; round++) {
            String id = released();
            ExecutorService pool = Executors.newFixedThreadPool(2);
            try {
                CyclicBarrier barrier = new CyclicBarrier(2);
                Future<Object> w = pool.submit(() -> { barrier.await(); try { return reporting.withdrawRelease(id, null, "wd-" + id, "p1").status(); } catch (BusinessException e) { return e.code(); } });
                Future<Object> h = pool.submit(() -> { barrier.await(); try { release.hold(id, HoldReason.MANUAL, "并发", "p2"); return "HELD"; } catch (BusinessException e) { return e.code(); } });
                Object wr = w.get(30, TimeUnit.SECONDS), hr = h.get(30, TimeUnit.SECONDS);
                String status = jdbc.queryForObject("select status from work_order where id = ?", String.class, id);
                assertThat(status).isIn("CREATED", "ON_HOLD");
                if ("CREATED".equals(status)) assertThat(wr).isEqualTo(200);
                else assertThat(hr).isEqualTo("HELD");
            } finally { pool.shutdownNow(); }
        }
    }
}
