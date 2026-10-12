package com.smartmanufacturing.inventory;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.smartmanufacturing.inventory.ReservationDtos.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import static org.assertj.core.api.Assertions.*;

/**
 * 预留在真实 MySQL 8.4 上的阻塞门禁：并发不超卖、跨物料无死锁、过期由库存判定并发事件、outbox 回滚不发与补发 eventId 不变、
 * DECIMAL(18,6) 精度。H2 的行锁与 SKIP LOCKED 语义与 MySQL 不同，这些行为只在本 IT 中验证。
 */
@Testcontainers
@SpringBootTest
@ActiveProfiles("test")
class ReservationMySqlIT {
    @Container @ServiceConnection
    static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.4");

    @Autowired JdbcTemplate jdbc;
    @Autowired TransactionTemplate tx;
    @Autowired StockService stock;
    @Autowired LocationService locations;
    @Autowired ReservationService reservations;
    @Autowired IdempotencyService idempotency;
    @Autowired ReservationExpiryJob expiry;
    @Autowired OutboxPublisher publisher;
    @Autowired OutboxWriter outbox;
    @Autowired RecordingEventSender sender;
    @Autowired ObjectMapper mapper;

    @BeforeEach void setUp() {
        if (jdbc.queryForObject("select count(*) from warehouse_location where code = 'IT-R-01'", Integer.class) == 0)
            locations.create(new InventoryDtos.CreateLocation("IT-R-01", "WH", "IT"));
        // 每个用例从干净的 outbox 开始，避免其他用例的事件影响断言。
        publisher.publishBatch();
        sender.clear();
    }

    private void inbound(String material, String uom, String lot, String qty) {
        tx.executeWithoutResult(s -> stock.inbound("it", new InventoryDtos.Movement(material, uom, "IT-R-01", lot, qty, null)));
    }
    /** 经幂等执行器创建（与 HTTP 入口相同的事务边界与死锁重试）。 */
    private Reservation reserve(String key, CreateReservation r) throws Exception {
        String body = LockRetry.run(() -> idempotency.execute("workorder-service", key, "reservation.create", r, 201,
            () -> reservations.create("workorder-service", r))).body();
        return mapper.readValue(body, Reservation.class);
    }
    private static CreateReservation order(String wo, CreateLine... lines) { return new CreateReservation(wo, List.of(lines)); }
    private static CreateLine line(String m, String qty, String uom, String src) { return new CreateLine(m, qty, uom, src); }
    private BigDecimal reserved(String material) { return stock.activeReserved(material); }

    /**
     * 并发不超卖：可用 10，20 个线程各预留 1，恰好 10 张成功，有效预留合计 10。
     * 同时断言每个预留事务实际运行在 READ COMMITTED 下（读取会话的 @@transaction_isolation），
     * 确保本用例验证的正是生产路径使用的隔离级别，而不是 MySQL 默认的 REPEATABLE READ。
     */
    @Test void concurrentReservationsNeverOversell() throws Exception {
        inbound("IT-R-C", "件", "L1", "10");
        Set<String> isolations = java.util.concurrent.ConcurrentHashMap.newKeySet();
        Map<String, Integer> outcome = race(20, i -> {
            CreateReservation r = order("WO-C-" + i, line("IT-R-C", "1", "件", "WO-C-" + i + ":1"));
            String body = LockRetry.run(() -> idempotency.execute("workorder-service", UUID.randomUUID().toString(), "reservation.create", r, 201,
                () -> {
                    // 在幂等事务内读取当前会话隔离级别。
                    isolations.add(jdbc.queryForObject("select @@transaction_isolation", String.class));
                    return reservations.create("workorder-service", r);
                })).body();
            return mapper.readValue(body, Reservation.class);
        });
        assertThat(isolations).containsExactly("READ-COMMITTED");
        assertThat(outcome).containsEntry("OK", 10).containsEntry("insufficient-stock", 10);
        assertThat(reserved("IT-R-C")).isEqualByComparingTo("10");
    }

    /** 跨物料：一半线程按 A,B、一半按 B,A 提交，服务端按物料编码排序加锁，不出现死锁异常，且不超卖。 */
    @Test void crossMaterialReservationsDoNotDeadlock() throws Exception {
        inbound("IT-R-X1", "件", "L1", "5");
        inbound("IT-R-X2", "件", "L1", "5");
        Map<String, Integer> outcome = race(16, i -> {
            CreateLine a = line("IT-R-X1", "1", "件", "S-A"), b = line("IT-R-X2", "1", "件", "S-B");
            return reserve(UUID.randomUUID().toString(), order("WO-X-" + i, i % 2 == 0 ? a : b, i % 2 == 0 ? b : a));
        });
        assertThat(outcome).containsOnlyKeys("OK", "insufficient-stock").containsEntry("OK", 5);
        assertThat(reserved("IT-R-X1")).isEqualByComparingTo("5");
        assertThat(reserved("IT-R-X2")).isEqualByComparingTo("5");
    }

    /** 过期：由库存过期任务判定，状态 EXPIRED、占用归还、产生且仅产生一个 ReservationExpired；之后扣减与续期均 410。 */
    @Test void expiryIsDecidedByInventoryAndEmitsEvent() throws Exception {
        inbound("IT-R-E", "kg", "L1", "3");
        Reservation r = reserve(UUID.randomUUID().toString(), order("WO-E", line("IT-R-E", "3", "kg", "WO-E:1")));
        // 未到期：任务不处理。
        assertThat(expiry.expireDue()).isZero();
        jdbc.update("update reservation set expires_at = ? where id = ?", Timestamp.from(Instant.now().minusSeconds(5)), r.reservationId());
        // 两个实例同时执行：SKIP LOCKED 保证只过期一次。
        Map<String, Integer> runs = race(2, i -> { expiry.expireDue(); return null; });
        assertThat(runs).containsEntry("OK", 2);
        assertThat(jdbc.queryForObject("select status from reservation where id = ?", String.class, r.reservationId())).isEqualTo("EXPIRED");
        assertThat(jdbc.queryForObject("select count(*) from outbox_event where aggregate_id = ? and event_type = 'ReservationExpired'", Integer.class, r.reservationId())).isEqualTo(1);
        assertThat(reserved("IT-R-E")).isEqualByComparingTo("0");

        publisher.publishBatch();
        assertThat(sender.sent()).anySatisfy(s -> {
            assertThat(s.tag()).isEqualTo("ReservationExpired");
            assertThat(s.key()).isEqualTo(r.reservationId());
            assertThat(s.payload()).contains("\"status\":\"EXPIRED\"", "\"workOrderNo\":\"WO-E\"");
        });
        String lineId = r.lines().get(0).lineId();
        assertThatThrownBy(() -> tx.execute(s -> reservations.consume(r.reservationId(), lineId, new ConsumeRequest("1", "L1"))))
            .isInstanceOfSatisfying(ProblemException.class, e -> assertThat(e.type()).isEqualTo(ProblemType.RESERVATION_EXPIRED));
        assertThatThrownBy(() -> tx.execute(s -> reservations.renew(r.reservationId())))
            .isInstanceOfSatisfying(ProblemException.class, e -> assertThat(e.type().status().value()).isEqualTo(410));
    }

    /** outbox：业务事务回滚则没有事件；投递失败后补发，eventId 不变。 */
    @Test void outboxRollbackAndResendKeepEventId() throws Exception {
        int before = jdbc.queryForObject("select count(*) from outbox_event", Integer.class);
        // 事件已写入 outbox，但随后业务失败回滚：事件必须随之消失。
        assertThatThrownBy(() -> tx.executeWithoutResult(s -> {
            outbox.append("ReservationCreated", "rollback-id", "WO-RB", "ACTIVE", Map.of());
            throw new IllegalStateException("模拟业务失败");
        })).isInstanceOf(IllegalStateException.class);
        // 不足导致的 409 同样不留事件。
        assertThatThrownBy(() -> reserve(UUID.randomUUID().toString(), order("WO-RB2", line("IT-R-NONE", "1", "kg", "S1"))))
            .isInstanceOf(ProblemException.class);
        assertThat(jdbc.queryForObject("select count(*) from outbox_event", Integer.class)).isEqualTo(before);

        inbound("IT-R-O", "kg", "L1", "1");
        Reservation r = reserve(UUID.randomUUID().toString(), order("WO-O", line("IT-R-O", "1", "kg", "WO-O:1")));
        String eventId = jdbc.queryForObject("select event_id from outbox_event where aggregate_id = ? and event_type = 'ReservationCreated'", String.class, r.reservationId());
        sender.failNext(1);
        assertThat(publisher.publishBatch()).isZero();
        Map<String, Object> failed = jdbc.queryForMap("select status, attempts, last_error from outbox_event where event_id = ?", eventId);
        assertThat(failed.get("status")).isEqualTo("NEW");
        assertThat(((Number) failed.get("attempts")).intValue()).isEqualTo(1);
        assertThat(failed.get("last_error")).isNotNull();
        // 退避到期后补发（直接把下次尝试时间调到过去，避免测试等待）。
        jdbc.update("update outbox_event set next_attempt_at = ? where event_id = ?", Timestamp.from(Instant.now().minusSeconds(1)), eventId);
        assertThat(publisher.publishBatch()).isEqualTo(1);
        assertThat(sender.sent()).singleElement().satisfies(s -> {
            assertThat(s.eventId()).isEqualTo(eventId);
            assertThat(s.tag()).isEqualTo("ReservationCreated");
            JsonNode payload = mapper.readTree(s.payload());
            assertThat(payload.get("eventId").asText()).isEqualTo(eventId);
            assertThat(payload.get("lines").get(0).get("uom").asText()).isEqualTo("kg");
        });
        assertThat(jdbc.queryForObject("select status from outbox_event where event_id = ?", String.class, eventId)).isEqualTo("SENT");
    }

    /** DECIMAL(18,6)：预留与扣减按最小单位 0.000001 精确累计，最大值也不丢精度；扣完自动 CONSUMED。 */
    @Test void decimalPrecisionForReserveAndConsume() throws Exception {
        inbound("IT-R-P", "kg", "L1", "1.000003");
        Reservation r = reserve(UUID.randomUUID().toString(), order("WO-P",
            line("IT-R-P", "0.000001", "kg", "WO-P:1"), line("IT-R-P", "1.000002", "kg", "WO-P:2")));
        assertThat(reserved("IT-R-P")).isEqualByComparingTo("1.000003");
        // 再预留 0.000001 必然不足（可用为 0）。
        assertThatThrownBy(() -> reserve(UUID.randomUUID().toString(), order("WO-P2", line("IT-R-P", "0.000001", "kg", "S"))))
            .isInstanceOfSatisfying(ProblemException.class, e -> assertThat(e.type()).isEqualTo(ProblemType.INSUFFICIENT_STOCK));
        tx.execute(s -> reservations.consume(r.reservationId(), r.lines().get(1).lineId(), new ConsumeRequest("1.000001", "L1")));
        assertThatThrownBy(() -> tx.execute(s -> reservations.consume(r.reservationId(), r.lines().get(1).lineId(), new ConsumeRequest("0.000002", "L1"))))
            .isInstanceOfSatisfying(ProblemException.class, e -> assertThat(e.type()).isEqualTo(ProblemType.OVER_CONSUME));
        tx.execute(s -> reservations.consume(r.reservationId(), r.lines().get(1).lineId(), new ConsumeRequest("0.000001", "L1")));
        Reservation done = tx.execute(s -> reservations.consume(r.reservationId(), r.lines().get(0).lineId(), new ConsumeRequest("0.000001", "L1")));
        assertThat(done.status()).isEqualTo(Status.CONSUMED);
        assertThat(jdbc.queryForObject("select qty from stock_on_hand where material_code = 'IT-R-P'", BigDecimal.class)).isEqualByComparingTo("0");
        assertThat(jdbc.queryForObject("select sum(qty) from stock_txn where material_code = 'IT-R-P' and txn_type = 'CONSUME'", BigDecimal.class))
            .isEqualByComparingTo("-1.000003");

        inbound("IT-R-PMAX", "kg", "L1", "999999999999.999999");
        Reservation max = reserve(UUID.randomUUID().toString(), order("WO-PMAX", line("IT-R-PMAX", "999999999999.999999", "kg", "S")));
        assertThat(max.lines().get(0).reservedQty()).isEqualTo("999999999999.999999");
    }

    /** 并发执行 n 个任务（栅栏同时起跑），统计成功与各类业务错误。 */
    private static Map<String, Integer> race(int n, ThrowingTask task) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(n);
        CyclicBarrier barrier = new CyclicBarrier(n);
        try {
            List<Future<String>> fs = new ArrayList<>();
            for (int i = 0; i < n; i++) {
                int idx = i;
                fs.add(pool.submit(() -> {
                    barrier.await();
                    try { task.run(idx); return "OK"; } catch (ProblemException e) { return e.type().value(); }
                }));
            }
            Map<String, Integer> out = new TreeMap<>();
            for (Future<String> f : fs) out.merge(f.get(60, TimeUnit.SECONDS), 1, Integer::sum);
            return out;
        } finally { pool.shutdownNow(); }
    }

    @FunctionalInterface interface ThrowingTask { Object run(int i) throws Exception; }
}
