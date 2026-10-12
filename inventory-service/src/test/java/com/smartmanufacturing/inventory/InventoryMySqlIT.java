package com.smartmanufacturing.inventory;

import com.smartmanufacturing.inventory.InventoryDtos.Movement;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import java.math.BigDecimal;
import java.util.*;
import java.util.concurrent.*;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * 真实 MySQL 8.4 集成测试：表结构来自 Flyway V1（不加载演示数据），验证 H2 无法可靠覆盖的行为——
 * DECIMAL(18,6) 精度边界、SELECT ... FOR UPDATE 下的并发出库不超卖、并发同键幂等只入库一次。
 */
@Testcontainers
@SpringBootTest
@ActiveProfiles("test")
class InventoryMySqlIT {
    @Container @ServiceConnection
    static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.4");

    @Autowired JdbcTemplate jdbc;
    @Autowired StockService stock;
    @Autowired IdempotencyService idempotency;
    @Autowired LocationService locations;

    private IdempotencyService.StoredResponse inbound(String key, Movement m) {
        return LockRetry.run(() -> idempotency.execute("it", key, "inbound", m, 201, () -> stock.inbound("it", m)));
    }
    private IdempotencyService.StoredResponse outbound(String key, Movement m) {
        return LockRetry.run(() -> idempotency.execute("it", key, "outbound", m, 201, () -> stock.outbound("it", m)));
    }
    private void location(String code) { locations.create(new InventoryDtos.CreateLocation(code, "WH", code)); }
    private BigDecimal onHand(String material) {
        return jdbc.queryForObject("select coalesce(sum(qty), 0) from stock_on_hand where material_code = ?", BigDecimal.class, material);
    }

    /** 表结构必须来自 Flyway V1，且数量列为 DECIMAL(18,6)。 */
    @Test void schemaComesFromFlywayV1WithDecimal18x6() {
        assertThat(jdbc.queryForObject("select count(*) from flyway_schema_history where version = '1' and success = 1", Integer.class)).isEqualTo(1);
        List<String> types = jdbc.queryForList("select distinct column_type from information_schema.columns where table_schema = database()"
            + " and column_name in ('qty', 'balance_after', 'reserved_qty', 'consumed_qty')", String.class);
        assertThat(types).containsExactly("decimal(18,6)");
    }

    /** 精度边界：最大值入库、最小单位逐笔累加与出库都必须精确，不得丢失第 6 位小数。 */
    @Test void decimalPrecisionIsExact() {
        location("IT-P-01");
        inbound(UUID.randomUUID().toString(), new Movement("IT-P-MAX", "kg", "IT-P-01", "L1", "999999999999.999999", null));
        assertThat(onHand("IT-P-MAX")).isEqualByComparingTo("999999999999.999999");
        outbound(UUID.randomUUID().toString(), new Movement("IT-P-MAX", "kg", "IT-P-01", "L1", "0.000001", null));
        assertThat(onHand("IT-P-MAX")).isEqualByComparingTo("999999999999.999998");

        for (int i = 0; i < 7; i++) inbound(UUID.randomUUID().toString(), new Movement("IT-P-MIN", "kg", "IT-P-01", "L1", "0.000001", null));
        assertThat(onHand("IT-P-MIN")).isEqualByComparingTo("0.000007");
        // 流水合计必须等于余额（流水与余额同事务写入）。
        assertThat(jdbc.queryForObject("select sum(qty) from stock_txn where material_code = 'IT-P-MIN'", BigDecimal.class)).isEqualByComparingTo("0.000007");
    }

    /** 并发出库不超卖：库存 10，20 个线程各出库 1，必须恰好 10 个成功，余额 0，流水条数 = 成功数。 */
    @Test void concurrentOutboundNeverOversells() throws Exception {
        location("IT-C-01");
        inbound(UUID.randomUUID().toString(), new Movement("IT-C", "件", "IT-C-01", "L1", "10", null));
        int threads = 20;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CyclicBarrier barrier = new CyclicBarrier(threads);
        List<Future<String>> results = new ArrayList<>();
        try {
            for (int i = 0; i < threads; i++) results.add(pool.submit(() -> {
                barrier.await();
                try { outbound(UUID.randomUUID().toString(), new Movement("IT-C", "件", "IT-C-01", "L1", "1", null)); return "OK"; }
                catch (ProblemException e) { return e.type().value(); }
            }));
            Map<String, Integer> count = new HashMap<>();
            for (Future<String> f : results) count.merge(f.get(60, TimeUnit.SECONDS), 1, Integer::sum);
            assertThat(count).containsEntry("OK", 10).containsEntry("insufficient-stock", 10);
        } finally { pool.shutdownNow(); }
        assertThat(onHand("IT-C")).isEqualByComparingTo("0");
        assertThat(jdbc.queryForObject("select count(*) from stock_txn where material_code = 'IT-C' and txn_type = 'OUTBOUND'", Integer.class)).isEqualTo(10);
    }

    /** 并发同键：10 个线程用同一个幂等键入库，只能入库一次，所有线程拿到相同的响应体。 */
    @Test void concurrentSameKeyInboundsOnce() throws Exception {
        location("IT-K-01");
        String key = UUID.randomUUID().toString();
        Movement m = new Movement("IT-K", "kg", "IT-K-01", "L1", "5", null);
        int threads = 10;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CyclicBarrier barrier = new CyclicBarrier(threads);
        Set<String> bodies = ConcurrentHashMap.newKeySet();
        try {
            List<Future<?>> fs = new ArrayList<>();
            for (int i = 0; i < threads; i++) fs.add(pool.submit(() -> { barrier.await(); bodies.add(inbound(key, m).body()); return null; }));
            for (Future<?> f : fs) f.get(60, TimeUnit.SECONDS);
        } finally { pool.shutdownNow(); }
        assertThat(bodies).hasSize(1);
        assertThat(onHand("IT-K")).isEqualByComparingTo("5");
    }
}
