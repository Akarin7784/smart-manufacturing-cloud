package com.smartmanufacturing.inventory;

import com.smartmanufacturing.inventory.InventoryDtos.Movement;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.concurrent.*;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * #57 回归（真实 MySQL 8.4）：两个事务并发做方向相反的移库（A→QC-HOLD 与 QC-HOLD→A），
 * 每轮用 CyclicBarrier 让两者同时开始加锁，重复多轮。两步加锁（候选 id → 一次性按主键升序 FOR UPDATE）
 * 下两边加锁顺序一致，不应出现死锁；且总量守恒。不经 LockRetry，死锁会直接抛出并让用例失败。
 */
@Testcontainers
@SpringBootTest
@ActiveProfiles("test")
class StockTransferDeadlockMySqlIT {
    @Container @ServiceConnection
    static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.4");

    @Autowired JdbcTemplate jdbc;
    @Autowired StockService stock;
    @Autowired LocationService locations;
    @Autowired IdempotencyService idempotency;
    @Autowired PlatformTransactionManager txManager;

    private static final String MATERIAL = "IT-MV", LOT = "B1", LOC = "IT-MV-A";

    /** 在一个 READ COMMITTED 事务里一次性锁住源与目标两行，源减 qty、目标加 qty。 */
    private void move(TransactionTemplate tx, String from, String to, BigDecimal qty) {
        tx.executeWithoutResult(status -> {
            var src = new StockService.RowKey(MATERIAL, from, LOT);
            var dst = new StockService.RowKey(MATERIAL, to, LOT);
            Map<StockService.RowKey, StockService.LockedRow> rows = stock.lockRows(List.of(src, dst), true);
            StockService.LockedRow s = rows.get(src), d = rows.get(dst);
            // 锁内校验余额：不足就跳过本轮（不同轮次方向相反，余额会来回变化）。
            if (s.qty().compareTo(qty) < 0) return;
            stock.applyDelta(s, MATERIAL, "件", qty.negate(), StockService.OUTBOUND, "IT", "move", "it");
            stock.applyDelta(d, MATERIAL, "件", qty, StockService.INBOUND, "IT", "move", "it");
        });
    }

    @Test void oppositeTransfersBetweenLocationAndQcHoldDoNotDeadlock() throws Exception {
        locations.create(new InventoryDtos.CreateLocation(LOC, "WH", LOC));
        Movement m = new Movement(MATERIAL, "件", LOC, LOT, "100", "IT");
        idempotency.execute("it", "mv-init", "inbound", m, 201, () -> stock.inbound("it", m));
        idempotency.execute("it", "mv-hold", "qc-hold.receipt", "mv-hold", 201,
            () -> stock.qcHoldIn("it", MATERIAL, "件", LOT, new BigDecimal("100"), "NCR-20261013-0001"));

        TransactionTemplate tx = new TransactionTemplate(txManager);
        tx.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            for (int round = 0; round < 50; round++) {
                CyclicBarrier start = new CyclicBarrier(2);
                Future<?> forward = pool.submit(() -> { start.await(); move(tx, LOC, StockService.QC_HOLD, BigDecimal.ONE); return null; });
                Future<?> backward = pool.submit(() -> { start.await(); move(tx, StockService.QC_HOLD, LOC, BigDecimal.ONE); return null; });
                // 任一方向抛出死锁（CannotAcquireLockException）都会在 get() 处让用例失败。
                forward.get(30, TimeUnit.SECONDS);
                backward.get(30, TimeUnit.SECONDS);
            }
        } finally {
            pool.shutdownNow();
        }
        BigDecimal total = jdbc.queryForObject("select sum(qty) from stock_on_hand where material_code = ?", BigDecimal.class, MATERIAL);
        assertThat(total).isEqualByComparingTo("200");
    }
}
