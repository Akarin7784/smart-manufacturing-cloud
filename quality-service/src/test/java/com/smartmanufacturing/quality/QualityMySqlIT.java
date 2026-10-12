package com.smartmanufacturing.quality;

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
import java.util.function.IntFunction;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * 质检在真实 MySQL 8.4 上的门禁：Flyway V1、并发处置不超处置（行锁 + READ COMMITTED）、并发同键幂等重放、
 * 同一报工的报告与冲销并发消费不留孤儿暂存、DECIMAL(18,6) 精度。H2 的行锁语义与 MySQL 不同，这些行为只在本 IT 验证。
 */
@Testcontainers
@SpringBootTest
@ActiveProfiles("test")
class QualityMySqlIT {
    @Container @ServiceConnection
    static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.4");

    @Autowired JdbcTemplate jdbc;
    @Autowired DefectEventHandler handler;
    @Autowired NcrService ncrs;
    @Autowired IdempotencyService idempotency;

    private String openUnderReview(String qty) {
        String reportId = "RPT-MY-" + UUID.randomUUID();
        handler.handle("DefectReported", DefectEvents.id(), DefectEvents.reported(DefectEvents.id(), reportId, "WO-MY", qty, "件"));
        String ncrNo = jdbc.queryForObject("select ncr_no from ncr where source_report_id = ?", String.class, reportId);
        idempotency.execute("qa", UUID.randomUUID().toString(), "ncr.review", ncrNo, 200, () -> ncrs.review(ncrNo));
        return ncrNo;
    }

    /** 并发执行 n 个任务，返回各任务结果（异常时为异常对象）。 */
    private static List<Object> race(int n, IntFunction<Object> task) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(n);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<Object>> futures = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            int idx = i;
            futures.add(pool.submit(() -> {
                start.await();
                try { return task.apply(idx); } catch (RuntimeException e) { return e; }
            }));
        }
        start.countDown();
        List<Object> results = new ArrayList<>();
        for (Future<Object> f : futures) results.add(f.get(60, TimeUnit.SECONDS));
        pool.shutdown();
        return results;
    }

    @Test void flywayCreatedAllTables() {
        List<String> tables = jdbc.queryForList("select table_name from information_schema.tables where table_schema = database()", String.class);
        assertThat(tables).contains("ncr", "ncr_disposition", "consumed_event", "pending_reversal", "ncr_conflict", "outbox_event",
            "idempotency_record", "flyway_schema_history");
    }

    /** 不良 10，20 个线程各处置 1：恰好 10 条成功，累计 10、DISPOSED；其余为 over-dispose 或 invalid-status-transition。事务均为 READ COMMITTED。 */
    @Test void concurrentDispositionsNeverOverDispose() throws Exception {
        String ncrNo = openUnderReview("10");
        Set<String> isolations = ConcurrentHashMap.newKeySet();
        List<Object> results = race(20, i -> LockRetry.run(() -> idempotency.execute("qa-" + i, UUID.randomUUID().toString(), "ncr.dispose",
            Map.of("ncrNo", ncrNo, "i", i), 201, () -> {
                isolations.add(jdbc.queryForObject("select @@transaction_isolation", String.class));
                return ncrs.dispose(ncrNo, "qa-" + i, new NcrDtos.CreateDisposition("SCRAP", "1", "件", null));
            })));
        long ok = results.stream().filter(r -> r instanceof IdempotencyService.StoredResponse).count();
        assertThat(ok).isEqualTo(10);
        assertThat(results.stream().filter(r -> !(r instanceof IdempotencyService.StoredResponse)))
            .allSatisfy(r -> assertThat(((ProblemException) r).type()).isIn(ProblemType.OVER_DISPOSE, ProblemType.INVALID_STATUS_TRANSITION));
        assertThat(jdbc.queryForObject("select disposed_qty from ncr where ncr_no = ?", BigDecimal.class, ncrNo)).isEqualByComparingTo("10");
        assertThat(jdbc.queryForObject("select sum(qty) from ncr_disposition where ncr_no = ?", BigDecimal.class, ncrNo)).isEqualByComparingTo("10");
        assertThat(jdbc.queryForObject("select status from ncr where ncr_no = ?", String.class, ncrNo)).isEqualTo("DISPOSED");
        assertThat(isolations).containsExactly("READ-COMMITTED");
    }

    /** 并发同键同内容：只执行一次业务（1 条处置行），所有响应与首次逐字节一致。 */
    @Test void concurrentSameKeyReplaysWinner() throws Exception {
        String ncrNo = openUnderReview("5");
        String key = UUID.randomUUID().toString();
        NcrDtos.CreateDisposition req = new NcrDtos.CreateDisposition("REWORK", "0.000001", "件", "精度");
        List<Object> results = race(8, i -> LockRetry.run(() -> idempotency.execute("qa", key, "ncr.dispose",
            Map.of("ncrNo", ncrNo, "request", req), 201, () -> ncrs.dispose(ncrNo, "qa", req))));
        assertThat(results).allMatch(r -> r instanceof IdempotencyService.StoredResponse);
        assertThat(results.stream().map(r -> ((IdempotencyService.StoredResponse) r).body()).distinct()).hasSize(1);
        assertThat(jdbc.queryForObject("select count(*) from ncr_disposition where ncr_no = ?", Integer.class, ncrNo)).isEqualTo(1);
        assertThat(jdbc.queryForObject("select disposed_qty from ncr where ncr_no = ?", BigDecimal.class, ncrNo)).isEqualByComparingTo("0.000001");
    }

    /** 同一报工的 Reported 与 Reversed 并发消费（报工级锁串行）：结果必为 VOIDED 的 NCR，不会留下 WAITING 的孤儿暂存。 */
    @Test void concurrentReportAndReversalNeverLeaveOrphanPending() throws Exception {
        for (int round = 0; round < 15; round++) {
            String reportId = "RPT-RACE-" + UUID.randomUUID();
            race(2, i -> i == 0
                ? handler.handle("DefectReported", DefectEvents.id(), DefectEvents.reported(DefectEvents.id(), reportId, "WO-R", "1", "件"))
                : handler.handle("DefectReversed", DefectEvents.id(), DefectEvents.reversed(DefectEvents.id(), reportId, "REV-R")));
            assertThat(jdbc.queryForObject("select status from ncr where source_report_id = ?", String.class, reportId)).isEqualTo("VOIDED");
            assertThat(jdbc.queryForObject("select count(*) from pending_reversal where reversed_report_id = ? and status = 'WAITING'",
                Integer.class, reportId)).isZero();
        }
    }

    /** 同一 eventId 并发投递：只消费一次。 */
    @Test void concurrentDuplicateEventsCreateOneNcr() throws Exception {
        String reportId = "RPT-DUP-" + UUID.randomUUID();
        String eventId = DefectEvents.id();
        List<Object> results = race(6, i -> handler.handle("DefectReported", eventId, DefectEvents.reported(eventId, reportId, "WO-D", "1", "件")));
        assertThat(results).filteredOn(r -> r == DefectEventHandler.Result.OK).hasSize(1);
        assertThat(jdbc.queryForObject("select count(*) from ncr where source_report_id = ?", Integer.class, reportId)).isEqualTo(1);
    }
}
