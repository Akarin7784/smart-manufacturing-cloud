package com.smartmanufacturing.quality;

import com.smartmanufacturing.quality.InventoryQcHoldClient.Action;
import com.smartmanufacturing.quality.InventoryQcHoldClient.Rejected;
import com.smartmanufacturing.quality.InventoryQcHoldClient.Retryable;
import com.smartmanufacturing.quality.NcrDtos.WritebackStatus;
import com.smartmanufacturing.quality.NcrDtos.WritebackTask;
import org.junit.jupiter.api.AfterEach;
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
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * 库存回写在真实 MySQL 8.4 上的门禁：V3 时间列精度 TIMESTAMP(3)、同一毫秒写入同一毫秒领取（#46）、
 * 多实例并发领取（SKIP LOCKED，每个任务只调用一次）、作废与领取的行锁互斥（两种先后顺序），以及作废时机、重试窗口、关闭 409、
 * 人工结案在 MySQL 上的完整链路。等待锁用 Awaitility 轮询 information_schema 的锁等待，不用固定 sleep；时间由 {@link MutableClock} 控制。
 */
@Testcontainers
@SpringBootTest
@ActiveProfiles("test")
class WritebackMySqlIT {
    @Container @ServiceConnection
    static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.4");

    @Autowired JdbcTemplate jdbc;
    @Autowired TransactionTemplate tx;
    @Autowired DefectEventHandler handler;
    @Autowired NcrService ncrs;
    @Autowired IdempotencyService idempotency;
    @Autowired WritebackService writebacks;
    @Autowired WritebackWorker worker;
    @Autowired FakeInventoryQcHold inventory;
    @Autowired MutableClock clock;

    @BeforeEach void setUp() {
        clock.reset();
        inventory.clear();
        while (worker.runBatch() > 0) { /* drain */ }
        inventory.clear();
    }
    @AfterEach void tearDown() { clock.reset(); inventory.clear(); }

    private String rid() { return "RPT-WBMY-" + UUID.randomUUID(); }
    private String open(String reportId) {
        handler.handle("DefectReported", DefectEvents.id(), DefectEvents.reported(DefectEvents.id(), reportId, "WO-WBMY", "1", "件"));
        return jdbc.queryForObject("select ncr_no from ncr where source_report_id = ?", String.class, reportId);
    }
    private void reverse(String reportId) {
        handler.handle("DefectReversed", DefectEvents.id(), DefectEvents.reversed(DefectEvents.id(), reportId, "REV-" + UUID.randomUUID()));
    }
    private WritebackTask task(String ncrNo, Action action) {
        return writebacks.list(ncrNo).stream().filter(t -> t.action() == action).findFirst().orElseThrow();
    }
    private int lockWaits() {
        Integer n = jdbc.queryForObject("select count(*) from information_schema.innodb_trx where trx_state = 'LOCK WAIT'", Integer.class);
        return n == null ? 0 : n;
    }

    @Test void timeColumnsHaveMillisecondPrecision() {
        List<Map<String, Object>> cols = jdbc.queryForList("select column_name, datetime_precision from information_schema.columns"
            + " where table_schema = database() and table_name = 'inventory_writeback' and data_type = 'timestamp'");
        assertThat(cols).extracting(c -> String.valueOf(c.get("COLUMN_NAME")).toLowerCase())
            .containsExactlyInAnyOrder("first_attempted_at", "next_attempt_at", "resolved_at", "created_at", "updated_at");
        assertThat(cols).allSatisfy(c -> assertThat(((Number) c.get("DATETIME_PRECISION")).intValue()).isEqualTo(3));
    }

    /** 时钟固定在 .xxx7 毫秒：写入截断到毫秒，同一时刻领取仍能取到（MySQL 会把多余小数位四舍五入，不截断就会晚 1ms）。 */
    @Test void sameMillisecondWriteAndClaim() {
        clock.pin(Instant.now().truncatedTo(ChronoUnit.MILLIS).plusNanos(700_000));
        String ncr = open(rid());
        worker.runBatch();
        assertThat(inventory.callsOf(ncr)).hasSize(1);
        assertThat(task(ncr, Action.HOLD).status()).isEqualTo(WritebackStatus.DONE);
    }

    /** 两个“实例”并发领取：SKIP LOCKED 保证每个任务只调用库存一次。 */
    @Test void concurrentWorkersCallEachTaskOnce() throws Exception {
        List<String> ncrNos = new ArrayList<>();
        for (int i = 0; i < 30; i++) ncrNos.add(open(rid()));
        ExecutorService pool = Executors.newFixedThreadPool(4);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<?>> fs = new ArrayList<>();
        for (int i = 0; i < 4; i++) fs.add(pool.submit(() -> { start.await(); while (worker.runBatch() > 0) { /* drain */ } return null; }));
        start.countDown();
        for (Future<?> f : fs) f.get(60, TimeUnit.SECONDS);
        pool.shutdown();
        for (String n : ncrNos) {
            assertThat(inventory.callsOf(n)).as(n).hasSize(1);
            assertThat(task(n, Action.HOLD).status()).isEqualTo(WritebackStatus.DONE);
        }
    }

    /** 领取事务先锁住 HOLD 并记为已尝试：并发的作废事务等锁，提交后看到“已尝试”，追加 VOID 而不是取消 HOLD。 */
    @Test void voidWaitingForClaimAppendsVoid() throws Exception {
        String r = rid();
        String ncr = open(r);
        long holdId = task(ncr, Action.HOLD).id();
        CountDownLatch locked = new CountDownLatch(1), release = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        Future<?> claim = pool.submit(() -> tx.executeWithoutResult(s -> {
            jdbc.queryForObject("select id from inventory_writeback where id = ? for update", Long.class, holdId);
            jdbc.update("update inventory_writeback set attempts = 1, first_attempted_at = ? where id = ?",
                java.sql.Timestamp.from(clock.instant().truncatedTo(ChronoUnit.MILLIS)), holdId);
            locked.countDown();
            try { release.await(60, TimeUnit.SECONDS); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        }));
        locked.await(30, TimeUnit.SECONDS);
        Future<?> voiding = pool.submit(() -> reverse(r));
        await().atMost(Duration.ofSeconds(30)).until(() -> lockWaits() > 0);
        release.countDown();
        claim.get(30, TimeUnit.SECONDS);
        voiding.get(30, TimeUnit.SECONDS);
        pool.shutdown();
        assertThat(writebacks.list(ncr)).extracting(WritebackTask::action, WritebackTask::status)
            .containsExactly(org.assertj.core.groups.Tuple.tuple(Action.HOLD, WritebackStatus.PENDING),
                org.assertj.core.groups.Tuple.tuple(Action.VOID, WritebackStatus.PENDING));
    }

    /** 作废事务先锁住 HOLD：执行器领取时 SKIP LOCKED 跳过它，作废提交后 HOLD 为 CANCELLED，库存从未被调用。 */
    @Test void claimSkipsHoldLockedByVoid() throws Exception {
        String ncr = open(rid());
        CountDownLatch locked = new CountDownLatch(1), release = new CountDownLatch(1);
        ExecutorService pool = Executors.newSingleThreadExecutor();
        Future<?> voiding = pool.submit(() -> tx.executeWithoutResult(s -> {
            jdbc.queryForObject("select id from inventory_writeback where ncr_no = ? and action = 'HOLD' for update", Long.class, ncr);
            locked.countDown();
            try { release.await(60, TimeUnit.SECONDS); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            writebacks.onVoided(ncr);
        }));
        locked.await(30, TimeUnit.SECONDS);
        worker.runBatch();
        assertThat(inventory.callsOf(ncr)).isEmpty();
        release.countDown();
        voiding.get(30, TimeUnit.SECONDS);
        pool.shutdown();
        worker.runBatch();
        assertThat(inventory.callsOf(ncr)).isEmpty();
        assertThat(task(ncr, Action.HOLD).status()).isEqualTo(WritebackStatus.CANCELLED);
    }

    /** 端到端（MySQL）：HOLD 超时耗尽 → 作废追加 VOID 并保持 PENDING → 关闭被 409 阻止的路径与人工重试后 VOID 执行。 */
    @Test void uncertainHoldThenVoidThenRetry() {
        clock.pin(Instant.now().truncatedTo(ChronoUnit.MILLIS));
        String r = rid();
        String ncr = open(r);
        for (int i = 0; i < 20; i++) inventory.script("qc:" + ncr + ":hold", new Retryable("HTTP 504"));
        for (int i = 0; i < 20; i++) { worker.runBatch(); clock.advance(Duration.ofMinutes(5)); }
        assertThat(task(ncr, Action.HOLD).status()).isEqualTo(WritebackStatus.FAILED);
        reverse(r);
        assertThat(task(ncr, Action.VOID).status()).isEqualTo(WritebackStatus.PENDING);
        long holdId = task(ncr, Action.HOLD).id();
        tx.execute(s -> writebacks.retry(ncr, holdId));
        worker.runBatch();
        worker.runBatch();
        assertThat(writebacks.list(ncr)).extracting(WritebackTask::status).containsExactly(WritebackStatus.DONE, WritebackStatus.DONE);
    }

    /** 重试窗口在 MySQL 时间列上的边界，以及关闭 409 → 人工结案后可关闭。 */
    @Test void retryWindowAndCloseOnMySql() {
        clock.pin(Instant.now().truncatedTo(ChronoUnit.MILLIS));
        String ncr = open(rid());
        idempotency.execute("qa", UUID.randomUUID().toString(), "ncr.review", ncr, 200, () -> ncrs.review(ncr));
        idempotency.execute("qa", UUID.randomUUID().toString(), "ncr.dispose", UUID.randomUUID(), 201,
            () -> ncrs.dispose(ncr, "qa", new NcrDtos.CreateDisposition("SCRAP", "1", "件", null)));
        inventory.script("qc:" + ncr + ":disp:1:scrap", new Rejected("HTTP 409 insufficient-stock", true));
        worker.runBatch();
        worker.runBatch();
        long scrapId = task(ncr, Action.SCRAP).id();
        assertThat(task(ncr, Action.SCRAP).status()).isEqualTo(WritebackStatus.FAILED);
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> tx.execute(s -> ncrs.close(ncr)))
            .isInstanceOfSatisfying(ProblemException.class, e -> assertThat(e.type()).isEqualTo(ProblemType.WRITEBACK_PENDING));
        clock.advance(Duration.ofDays(6).plusMillis(1));
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> tx.execute(s -> writebacks.retry(ncr, scrapId)))
            .isInstanceOfSatisfying(ProblemException.class, e -> assertThat(e.type()).isEqualTo(ProblemType.INVALID_STATUS_TRANSITION));
        tx.execute(s -> writebacks.resolve(ncr, scrapId, "qa-lead", "超出重试窗口，已人工核对"));
        assertThat(tx.execute(s -> ncrs.close(ncr)).status()).isEqualTo(NcrDtos.Status.CLOSED);
    }
}
