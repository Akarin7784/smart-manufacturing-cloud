package com.smartmanufacturing.workorder;
import com.smartmanufacturing.api.workorder.HoldReason;
import com.smartmanufacturing.common.BusinessException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import java.util.*;
import java.util.concurrent.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * 真实 MySQL 8.4 并发测试：
 * <ul>
 *   <li>不同幂等键并发下达同一工单：恰好一个成功，其余 409，且失败方释放了自己的预留，库内只有一条预留；</li>
 *   <li>同一幂等键并发下达（客户端重试风暴）：全部成功返回同一预留单，库存侧不会被重复预留出多张单；</li>
 *   <li>并发冻结与过期事件：乐观锁重试后原因集合完整、resume_target 正确、事件只处理一次。</li>
 * </ul>
 */
@Testcontainers
@SpringBootTest
@ActiveProfiles("test")
class ConcurrentReleaseMySqlIT {
    @Container @ServiceConnection
    static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.4");
    private static final int THREADS = 6;
    private static final int ROUNDS = 10;

    @Autowired WorkOrderReleaseService service;
    @Autowired JdbcTemplate jdbc;
    @MockitoBean MasterdataClient masterdata;
    @MockitoBean DeviceClient devices;
    @MockitoSpyBean MaterialReservationPort materials;
    @MockitoBean org.springframework.security.oauth2.jwt.JwtDecoder decoder;

    @BeforeEach void setUp() { ReleaseFixtures.clear(jdbc); ReleaseFixtures.stubMasterdata(masterdata); }

    /** 在栅栏处同时启动全部任务；业务异常转为错误码，其他异常让测试失败。 */
    private static List<Object> race(List<Callable<?>> tasks) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(tasks.size());
        CyclicBarrier barrier = new CyclicBarrier(tasks.size());
        try {
            List<Future<Object>> fs = new ArrayList<>();
            for (Callable<?> c : tasks) fs.add(pool.submit(() -> { barrier.await(); try { return c.call(); } catch (BusinessException e) { return e.code(); } }));
            List<Object> out = new ArrayList<>();
            for (Future<Object> f : fs) out.add(f.get(60, TimeUnit.SECONDS));
            return out;
        } finally { pool.shutdownNow(); }
    }

    @Test void concurrentReleaseWithDifferentKeysHasSingleWinner() throws Exception {
        for (int round = 0; round < ROUNDS; round++) {
            clearInvocations(materials);
            String id = ReleaseFixtures.createOrder(jdbc, 10);
            List<Callable<?>> tasks = new ArrayList<>();
            for (int i = 0; i < THREADS; i++) { String key = "key-" + i; tasks.add(() -> service.release(id, key, "u")); }
            List<Object> results = race(tasks);
            long winners = results.stream().filter(r -> r instanceof WorkOrderReleaseService.ReleaseResult).count();
            assertThat(winners).as("round " + round + " " + results).isEqualTo(1);
            assertThat(results.stream().filter("WORK_ORDER_STATE_CONFLICT"::equals).count()).isEqualTo(THREADS - 1);
            assertThat(jdbc.queryForObject("select count(*) from wo_material_reservation where order_id = ?", Integer.class, id)).isEqualTo(1);
            assertThat(jdbc.queryForObject("select count(*) from operation_task where order_id = ?", Integer.class, id)).isEqualTo(2);
            assertThat(jdbc.queryForObject("select count(*) from outbox_event where aggregate_id = ?", Integer.class, id)).isEqualTo(1);
            // 每个真正向库存预留过的失败方都释放了自己的预留单（另一部分失败方在预留前就因状态已变被拒绝）。
            int reserved = mockingDetails(materials).getInvocations().stream().filter(i -> i.getMethod().getName().equals("reserve")).toList().size();
            verify(materials, times(reserved - 1)).release(anyString(), anyString(), eq(MaterialReservationPort.ReleaseReason.COMPENSATION));
        }
    }

    @Test void concurrentReleaseWithSameKeyReturnsSameReservation() throws Exception {
        for (int round = 0; round < ROUNDS; round++) {
            String id = ReleaseFixtures.createOrder(jdbc, 3);
            List<Callable<?>> tasks = new ArrayList<>();
            for (int i = 0; i < THREADS; i++) tasks.add(() -> service.release(id, "same", "u").view().reservation().reservationId());
            List<Object> results = race(tasks);
            assertThat(new HashSet<>(results)).as(results.toString()).hasSize(1);
            assertThat(jdbc.queryForObject("select count(*) from wo_material_reservation where order_id = ?", Integer.class, id)).isEqualTo(1);
            assertThat(jdbc.queryForObject("select count(*) from work_order_status_log where order_id = ?", Integer.class, id)).isEqualTo(1);
        }
        verify(materials, never()).release(anyString(), anyString(), any());
    }

    @Test void concurrentHoldsAndExpiryKeepReasonsConsistent() throws Exception {
        for (int round = 0; round < ROUNDS; round++) {
            String id = ReleaseFixtures.createOrder(jdbc, 3);
            String reservationId = service.release(id, "k", "u").view().reservation().reservationId();
            List<Callable<?>> tasks = List.of(
                () -> service.hold(id, HoldReason.MANUAL, null, "u"),
                () -> service.hold(id, HoldReason.LOT_HELD, null, "u"),
                () -> service.hold(id, HoldReason.FAI_FAILED, null, "u"),
                () -> service.onReservationExpired(reservationId),
                () -> service.onReservationExpired(reservationId));
            List<Object> results = race(tasks);
            assertThat(results).as(results.toString()).doesNotContain("CONCURRENT_MODIFICATION");
            var view = service.view(id);
            assertThat(view.status()).isEqualTo("ON_HOLD");
            assertThat(view.resumeTarget()).isEqualTo("RELEASED");
            assertThat(view.holdReasons()).containsExactlyInAnyOrder("FAI_FAILED", "LOT_HELD", "MANUAL", "MATERIAL_RESERVATION_EXPIRED");
            assertThat(view.version()).isEqualTo(4);
            assertThat(jdbc.queryForObject("select count(*) from consumed_event where event_id = ?", Integer.class, "ReservationExpired:" + reservationId)).isEqualTo(1);
        }
    }
}
