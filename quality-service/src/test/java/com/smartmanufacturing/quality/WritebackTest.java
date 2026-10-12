package com.smartmanufacturing.quality;

import com.smartmanufacturing.quality.InventoryQcHoldClient.Action;
import com.smartmanufacturing.quality.InventoryQcHoldClient.Booked;
import com.smartmanufacturing.quality.InventoryQcHoldClient.Call;
import com.smartmanufacturing.quality.InventoryQcHoldClient.Rejected;
import com.smartmanufacturing.quality.InventoryQcHoldClient.Retryable;
import com.smartmanufacturing.quality.NcrDtos.WritebackStatus;
import com.smartmanufacturing.quality.NcrDtos.WritebackTask;
import io.micrometer.core.instrument.MeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.support.TransactionTemplate;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 库存回写（H2，直接调用服务与执行器，库存为 {@link FakeInventoryQcHold}）：任务写入与键格式、同 NCR 串行、退避、
 * 作废时机的全部分支、重试窗口、关闭 409、人工结案与指标。时间全部由 {@link MutableClock} 固定与推进，不 sleep。
 * MySQL 上的行锁与 SKIP LOCKED 语义在 WritebackMySqlIT 再验证。
 */
@SpringBootTest
@ActiveProfiles("test")
class WritebackTest {
    @Autowired DefectEventHandler handler;
    @Autowired NcrService ncrs;
    @Autowired WritebackService writebacks;
    @Autowired WritebackWorker worker;
    @Autowired FakeInventoryQcHold inventory;
    @Autowired IdempotencyService idempotency;
    @Autowired JdbcTemplate jdbc;
    @Autowired TransactionTemplate tx;
    @Autowired MutableClock clock;
    @Autowired MeterRegistry registry;

    @BeforeEach void setUp() {
        clock.reset();
        inventory.clear();
        // 先把其他用例遗留的到期任务跑完，避免干扰本用例的批次与计数。
        while (worker.runBatch() > 0) { /* drain */ }
        inventory.clear();
        // 固定时钟（含亚毫秒），写入截断到毫秒后同一时刻仍能被领取（#46）。
        clock.pin(Instant.now().truncatedTo(ChronoUnit.MILLIS).plusNanos(700_000));
    }
    @AfterEach void tearDown() { clock.reset(); inventory.clear(); }

    // ---------------- 工具 ----------------

    private String rid() { return "RPT-WB-" + UUID.randomUUID(); }
    private String open(String reportId, String qty) {
        handler.handle("DefectReported", DefectEvents.id(), DefectEvents.reported(DefectEvents.id(), reportId, "WO-WB", qty, "件"));
        return jdbc.queryForObject("select ncr_no from ncr where source_report_id = ?", String.class, reportId);
    }
    private void reverse(String reportId) {
        handler.handle("DefectReversed", DefectEvents.id(), DefectEvents.reversed(DefectEvents.id(), reportId, "REV-" + UUID.randomUUID()));
    }
    private List<WritebackTask> tasks(String ncrNo) { return writebacks.list(ncrNo); }
    private WritebackTask task(String ncrNo, Action action) {
        return tasks(ncrNo).stream().filter(t -> t.action() == action).findFirst().orElseThrow();
    }
    private double count(String result) { return registry.get("mes.quality.writeback").tag("result", result).counter().count(); }
    private <T> T inTx(java.util.function.Supplier<T> s) { return tx.execute(st -> s.get()); }
    private void review(String ncrNo) {
        idempotency.execute("qa", UUID.randomUUID().toString(), "ncr.review", ncrNo, 200, () -> ncrs.review(ncrNo));
    }
    private void dispose(String ncrNo, String type, String qty) {
        idempotency.execute("qa", UUID.randomUUID().toString(), "ncr.dispose", UUID.randomUUID(), 201,
            () -> ncrs.dispose(ncrNo, "qa", new NcrDtos.CreateDisposition(type, qty, "件", null)));
    }
    /** 把某 NCR 的 HOLD 跑到重试耗尽（每次推进到下一次到期时间），结果为 FAILED（UNCERTAIN）。 */
    private void exhaustHold(String ncrNo) {
        String key = "qc:" + ncrNo + ":hold";
        for (int i = 0; i < 20; i++) inventory.script(key, new Retryable("HTTP 503"));
        for (int i = 0; i < 20; i++) { worker.runBatch(); clock.advance(Duration.ofMinutes(5)); }
    }

    // ---------------- 任务写入 ----------------

    @Test void openWritesHoldTaskWithFixedKeyAndPlainQty() {
        String ncr = open(rid(), "2.500");
        List<WritebackTask> t = tasks(ncr);
        assertThat(t).hasSize(1);
        assertThat(t.get(0).action()).isEqualTo(Action.HOLD);
        assertThat(t.get(0).idempotencyKey()).isEqualTo("qc:" + ncr + ":hold");
        assertThat(t.get(0).qty()).isEqualTo("2.5");
        assertThat(t.get(0).status()).isEqualTo(WritebackStatus.PENDING);
        assertThat(t.get(0).dispositionId()).isNull();
        assertThat(t.get(0).reason()).isNull();
        // next_attempt_at 截断到毫秒，与固定时钟同一毫秒内即可领取。
        assertThat(t.get(0).nextAttemptAt()).isEqualTo(clock.instant().truncatedTo(ChronoUnit.MILLIS));
        worker.runBatch();
        Call c = inventory.callsOf(ncr).get(0);
        assertThat(c.qty()).isEqualTo("2.5");
        assertThat(c.lotNo()).isEqualTo("LOT-1");
        assertThat(c.materialCode()).isEqualTo("FG-GR60");
        WritebackTask done = task(ncr, Action.HOLD);
        assertThat(done.status()).isEqualTo(WritebackStatus.DONE);
        assertThat(done.inventoryTxnId()).isNotNull();
        assertThat(done.nextAttemptAt()).isNull();
    }

    @Test void reversedBeforeReportedWritesNoTask() {
        String r = rid();
        reverse(r);
        String ncr = open(r, "1");
        assertThat(jdbc.queryForObject("select status from ncr where ncr_no = ?", String.class, ncr)).isEqualTo("VOIDED");
        assertThat(tasks(ncr)).isEmpty();
    }

    @Test void dispositionsWriteScrapAndReleaseTasksAndRunSeriallyAfterHold() {
        String ncr = open(rid(), "5");
        review(ncr);
        dispose(ncr, "SCRAP", "2");
        dispose(ncr, "REWORK", "2");
        dispose(ncr, "CONCESSION", "1");
        List<WritebackTask> t = tasks(ncr);
        assertThat(t).extracting(WritebackTask::idempotencyKey).containsExactly("qc:" + ncr + ":hold",
            "qc:" + ncr + ":disp:1:scrap", "qc:" + ncr + ":disp:2:release", "qc:" + ncr + ":disp:3:release");
        assertThat(t).extracting(WritebackTask::reason).containsExactly(null, null, "REWORK", "CONCESSION");
        assertThat(t).extracting(WritebackTask::dispositionId).containsExactly(null, 1L, 2L, 3L);

        // HOLD 第一次 503：本批只执行 HOLD，后续任务因前序未完成而不执行；退避 2s，1s 后不执行，2s 后执行。
        inventory.script("qc:" + ncr + ":hold", new Retryable("HTTP 503"));
        double retry = count("retry");
        worker.runBatch();
        assertThat(inventory.callsOf(ncr)).hasSize(1);
        assertThat(count("retry")).isEqualTo(retry + 1);
        WritebackTask hold = task(ncr, Action.HOLD);
        assertThat(hold.attempts()).isEqualTo(1);
        assertThat(hold.lastError()).isEqualTo("HTTP 503");
        assertThat(hold.nextAttemptAt()).isEqualTo(clock.instant().truncatedTo(ChronoUnit.MILLIS).plusSeconds(2));
        clock.advance(Duration.ofSeconds(1));
        worker.runBatch();
        assertThat(inventory.callsOf(ncr)).hasSize(1);
        clock.advance(Duration.ofSeconds(1));
        for (int i = 0; i < 4; i++) worker.runBatch();
        assertThat(inventory.callsOf(ncr)).extracting(Call::action).containsExactly(Action.HOLD, Action.HOLD, Action.SCRAP, Action.RELEASE, Action.RELEASE);
        Call scrap = inventory.callsOf(ncr).get(2);
        assertThat(scrap.dispositionId()).isEqualTo(1L);
        assertThat(scrap.reason()).isNull();
        assertThat(tasks(ncr)).allSatisfy(x -> assertThat(x.status()).isEqualTo(WritebackStatus.DONE));
    }

    @Test void backoffDoublesUpToFiveMinutes() {
        assertThat(worker.backoff(1)).isEqualTo(Duration.ofSeconds(2));
        assertThat(worker.backoff(2)).isEqualTo(Duration.ofSeconds(4));
        assertThat(worker.backoff(8)).isEqualTo(Duration.ofSeconds(256));
        assertThat(worker.backoff(9)).isEqualTo(Duration.ofMinutes(5));
        assertThat(worker.backoff(20)).isEqualTo(Duration.ofMinutes(5));
    }

    @Test void retriesExhaustedBecomeFailedUncertain() {
        String ncr = open(rid(), "1");
        double failed = count("failed");
        exhaustHold(ncr);
        WritebackTask hold = task(ncr, Action.HOLD);
        assertThat(hold.status()).isEqualTo(WritebackStatus.FAILED);
        assertThat(hold.attempts()).isEqualTo(20);
        assertThat(hold.lastError()).contains("重试 20 次仍失败");
        assertThat(count("failed")).isEqualTo(failed + 1);
        assertThat(jdbc.queryForObject("select failure_kind from inventory_writeback where id = ?", String.class, hold.id())).isEqualTo("UNCERTAIN");
    }

    @Test void replayedBookingIsCountedAsReplay() {
        String ncr = open(rid(), "1");
        inventory.script("qc:" + ncr + ":hold", new Booked(77L, true));
        double replay = count("replay");
        worker.runBatch();
        assertThat(count("replay")).isEqualTo(replay + 1);
        assertThat(task(ncr, Action.HOLD).inventoryTxnId()).isEqualTo(77L);
    }

    @Test void notReadyClientLeavesTasksPending() {
        String ncr = open(rid(), "1");
        inventory.ready(false);
        assertThat(worker.runBatch()).isZero();
        assertThat(task(ncr, Action.HOLD).status()).isEqualTo(WritebackStatus.PENDING);
        assertThat(task(ncr, Action.HOLD).attempts()).isZero();
        assertThat(registry.get("mes.quality.writeback.pending").gauge().value()).isGreaterThanOrEqualTo(1);
    }

    // ---------------- 作废时机 ----------------

    /** HOLD 从未尝试（attempts=0 且不在执行中）→ HOLD CANCELLED，不写 VOID。 */
    @Test void voidBeforeHoldAttemptedCancelsHold() {
        String r = rid();
        String ncr = open(r, "1");
        double cancelled = count("cancelled");
        reverse(r);
        assertThat(tasks(ncr)).singleElement().satisfies(t -> {
            assertThat(t.action()).isEqualTo(Action.HOLD);
            assertThat(t.status()).isEqualTo(WritebackStatus.CANCELLED);
        });
        assertThat(count("cancelled")).isEqualTo(cancelled + 1);
        worker.runBatch();
        assertThat(inventory.callsOf(ncr)).isEmpty();
    }

    /** HOLD 正在执行时作废 → 追加 VOID；HOLD DONE 后执行 VOID（reason=NCR_VOIDED，不带 dispositionId）。 */
    @Test void voidWhileHoldInFlightAppendsVoidAfterHold() {
        String r = rid();
        String ncr = open(r, "3");
        inventory.during(c -> reverse(r));
        worker.runBatch();
        List<WritebackTask> t = tasks(ncr);
        assertThat(t).extracting(WritebackTask::action).containsExactly(Action.HOLD, Action.VOID);
        assertThat(t.get(0).status()).isEqualTo(WritebackStatus.DONE);
        assertThat(t.get(1).status()).isEqualTo(WritebackStatus.PENDING);
        assertThat(t.get(1).idempotencyKey()).isEqualTo("qc:" + ncr + ":void");
        worker.runBatch();
        Call v = inventory.callsOf(ncr).get(1);
        assertThat(v.action()).isEqualTo(Action.VOID);
        assertThat(v.reason()).isEqualTo("NCR_VOIDED");
        assertThat(v.dispositionId()).isNull();
        assertThat(v.qty()).isEqualTo("3");
        assertThat(task(ncr, Action.VOID).status()).isEqualTo(WritebackStatus.DONE);
    }

    /** HOLD 已尝试并在退避中（attempts=1）时作废 → 追加 VOID，VOID 排在 HOLD 之后。 */
    @Test void voidDuringBackoffAppendsVoid() {
        String r = rid();
        String ncr = open(r, "1");
        inventory.script("qc:" + ncr + ":hold", new Retryable("timeout"));
        worker.runBatch();
        reverse(r);
        assertThat(tasks(ncr)).extracting(WritebackTask::status).containsExactly(WritebackStatus.PENDING, WritebackStatus.PENDING);
        clock.advance(Duration.ofSeconds(2));
        worker.runBatch();
        worker.runBatch();
        assertThat(inventory.callsOf(ncr)).extracting(Call::action).containsExactly(Action.HOLD, Action.HOLD, Action.VOID);
    }

    /** HOLD 超时耗尽（结果不确定）后作废 → VOID 保持 PENDING；人工重试 HOLD 成功后执行 VOID。 */
    @Test void uncertainHoldKeepsVoidPendingUntilRetriedHoldSucceeds() {
        String r = rid();
        String ncr = open(r, "1");
        exhaustHold(ncr);
        reverse(r);
        WritebackTask v = task(ncr, Action.VOID);
        assertThat(v.status()).isEqualTo(WritebackStatus.PENDING);
        worker.runBatch();
        assertThat(inventory.callsOf(ncr)).extracting(Call::action).doesNotContain(Action.VOID);
        assertThat(task(ncr, Action.VOID).status()).isEqualTo(WritebackStatus.PENDING);

        WritebackTask hold = task(ncr, Action.HOLD);
        WritebackTask retried = inTx(() -> writebacks.retry(ncr, hold.id()));
        assertThat(retried.status()).isEqualTo(WritebackStatus.PENDING);
        assertThat(retried.attempts()).isZero();
        assertThat(retried.idempotencyKey()).isEqualTo(hold.idempotencyKey());
        worker.runBatch();
        worker.runBatch();
        assertThat(tasks(ncr)).extracting(WritebackTask::status).containsExactly(WritebackStatus.DONE, WritebackStatus.DONE);
    }

    /** HOLD 结果不确定、人工确认库存未记账后 resolve → 其后的 VOID 自动 CANCELLED，NCR 可关闭判定不受阻。 */
    @Test void resolvingUncertainHoldCancelsVoid() {
        String r = rid();
        String ncr = open(r, "1");
        exhaustHold(ncr);
        reverse(r);
        double resolved = count("resolved_manually"), cancelled = count("cancelled");
        WritebackTask hold = task(ncr, Action.HOLD);
        WritebackTask res = inTx(() -> writebacks.resolve(ncr, hold.id(), "qa-lead", "  已核对库存，未记账  "));
        assertThat(res.status()).isEqualTo(WritebackStatus.RESOLVED_MANUALLY);
        assertThat(task(ncr, Action.VOID).status()).isEqualTo(WritebackStatus.CANCELLED);
        assertThat(count("resolved_manually")).isEqualTo(resolved + 1);
        assertThat(count("cancelled")).isEqualTo(cancelled + 1);
        var audit = jdbc.queryForMap("select resolved_by, resolved_reason, resolved_at from inventory_writeback where id = ?", hold.id());
        assertThat(audit.get("RESOLVED_BY")).isEqualTo("qa-lead");
        assertThat(audit.get("RESOLVED_REASON")).isEqualTo("已核对库存，未记账");
        assertThat(audit.get("RESOLVED_AT")).isNotNull();
    }

    /** HOLD 被明确拒绝（422 uom-mismatch）后作废 → VOID 直接 CANCELLED；之后不允许重试 HOLD。 */
    @Test void rejectedHoldThenVoidCancelsVoid() {
        String r = rid();
        String ncr = open(r, "1");
        inventory.script("qc:" + ncr + ":hold", new Rejected("HTTP 422 uom-mismatch", true));
        worker.runBatch();
        assertThat(task(ncr, Action.HOLD).status()).isEqualTo(WritebackStatus.FAILED);
        reverse(r);
        assertThat(task(ncr, Action.VOID).status()).isEqualTo(WritebackStatus.CANCELLED);
        long holdId = task(ncr, Action.HOLD).id();
        assertThatThrownBy(() -> inTx(() -> writebacks.retry(ncr, holdId)))
            .isInstanceOfSatisfying(ProblemException.class, e -> assertThat(e.type()).isEqualTo(ProblemType.INVALID_STATUS_TRANSITION));
    }

    /** 作废发生在 HOLD 执行中、HOLD 随后被明确拒绝（400）→ 执行器取消已追加的 VOID。 */
    @Test void holdRejectedAfterVoidAppendedCancelsVoid() {
        String r = rid();
        String ncr = open(r, "1");
        inventory.script("qc:" + ncr + ":hold", new Rejected("HTTP 400 validation-error", true));
        inventory.during(c -> reverse(r));
        double cancelled = count("cancelled");
        worker.runBatch();
        assertThat(tasks(ncr)).extracting(WritebackTask::status).containsExactly(WritebackStatus.FAILED, WritebackStatus.CANCELLED);
        assertThat(count("cancelled")).isEqualTo(cancelled + 1);
    }

    /** 拒绝本身不能证明未记账（certain=false）时，VOID 保持 PENDING。 */
    @Test void uncertainRejectionKeepsVoidPending() {
        String r = rid();
        String ncr = open(r, "1");
        inventory.script("qc:" + ncr + ":hold", new Rejected("HTTP 422 idempotency-key-conflict", false, InventoryQcHoldClient.Signal.CONTRACT));
        inventory.during(c -> reverse(r));
        double mismatch = registry.get("mes.quality.inventory.contract.mismatch").tag("operation", "hold").counter().count();
        worker.runBatch();
        assertThat(tasks(ncr)).extracting(WritebackTask::status).containsExactly(WritebackStatus.FAILED, WritebackStatus.PENDING);
        // 422 idempotency-key-conflict：FAILED 不重试，计契约不一致（operation=hold）。
        assertThat(registry.get("mes.quality.inventory.contract.mismatch").tag("operation", "hold").counter().count()).isEqualTo(mismatch + 1);
    }

    // ---------------- 人工重试、结案与关闭 ----------------

    @Test void retryOnlyFailedAndWithinWindow() {
        String ncr = open(rid(), "1");
        long id = task(ncr, Action.HOLD).id();
        // PENDING 不可重试。
        assertThatThrownBy(() -> inTx(() -> writebacks.retry(ncr, id)))
            .isInstanceOfSatisfying(ProblemException.class, e -> assertThat(e.type()).isEqualTo(ProblemType.INVALID_STATUS_TRANSITION));
        inventory.script("qc:" + ncr + ":hold", new Rejected("HTTP 400", true), new Rejected("HTTP 400", true));
        worker.runBatch();
        // 窗口末尾（创建时间 + P6D 前 1ms 以内；固定时钟带 0.7ms 亚毫秒，创建时间已截断到毫秒）仍可重试。
        clock.advance(Duration.ofDays(6).minusMillis(1));
        assertThat(inTx(() -> writebacks.retry(ncr, id)).status()).isEqualTo(WritebackStatus.PENDING);
        worker.runBatch();
        assertThat(task(ncr, Action.HOLD).status()).isEqualTo(WritebackStatus.FAILED);
        // 超过窗口：409。
        clock.advance(Duration.ofMillis(2));
        assertThatThrownBy(() -> inTx(() -> writebacks.retry(ncr, id)))
            .isInstanceOfSatisfying(ProblemException.class, e -> {
                assertThat(e.type()).isEqualTo(ProblemType.INVALID_STATUS_TRANSITION);
                assertThat(e.getMessage()).contains("重试窗口");
            });
        // 任务不属于该 NCR：404。
        String other = open(rid(), "1");
        assertThatThrownBy(() -> inTx(() -> writebacks.retry(other, id)))
            .isInstanceOfSatisfying(ProblemException.class, e -> assertThat(e.type()).isEqualTo(ProblemType.NCR_NOT_FOUND));
    }

    @Test void resolveRequiresReasonAndFailedStatus() {
        String ncr = open(rid(), "1");
        long id = task(ncr, Action.HOLD).id();
        assertThatThrownBy(() -> inTx(() -> writebacks.resolve(ncr, id, "qa", "   ")))
            .isInstanceOfSatisfying(ProblemException.class, e -> assertThat(e.type()).isEqualTo(ProblemType.VALIDATION_ERROR));
        assertThatThrownBy(() -> inTx(() -> writebacks.resolve(ncr, id, "qa", "核对")))
            .isInstanceOfSatisfying(ProblemException.class, e -> assertThat(e.type()).isEqualTo(ProblemType.INVALID_STATUS_TRANSITION));
    }

    @Test void closeIsBlockedUntilAllWritebacksAreTerminal() {
        String ncr = open(rid(), "1");
        review(ncr);
        dispose(ncr, "SCRAP", "1");
        inventory.script("qc:" + ncr + ":disp:1:scrap", new Rejected("HTTP 409 insufficient-stock", true));
        // HOLD、SCRAP 都 PENDING → 409。
        assertThatThrownBy(() -> inTx(() -> ncrs.close(ncr)))
            .isInstanceOfSatisfying(ProblemException.class, e -> assertThat(e.type()).isEqualTo(ProblemType.WRITEBACK_PENDING));
        worker.runBatch();
        worker.runBatch();
        // HOLD DONE、SCRAP FAILED → 仍 409。
        assertThat(tasks(ncr)).extracting(WritebackTask::status).containsExactly(WritebackStatus.DONE, WritebackStatus.FAILED);
        assertThatThrownBy(() -> inTx(() -> ncrs.close(ncr)))
            .isInstanceOfSatisfying(ProblemException.class, e -> assertThat(e.type()).isEqualTo(ProblemType.WRITEBACK_PENDING));
        // 人工结案后（终态含 RESOLVED_MANUALLY）可关闭。
        inTx(() -> writebacks.resolve(ncr, task(ncr, Action.SCRAP).id(), "qa", "库存已线下报废"));
        assertThat(inTx(() -> ncrs.close(ncr)).status()).isEqualTo(NcrDtos.Status.CLOSED);
    }

    @Test void terminalTasksArePurgedAfterRetention() {
        String r = rid();
        String ncr = open(r, "1");
        reverse(r);
        assertThat(task(ncr, Action.HOLD).status()).isEqualTo(WritebackStatus.CANCELLED);
        clock.advance(Duration.ofDays(30));
        writebacks.purgeTerminal(1000, 10);
        assertThat(jdbc.queryForObject("select count(*) from inventory_writeback where ncr_no = ?", Integer.class, ncr)).isOne();
        clock.advance(Duration.ofMillis(1));
        writebacks.purgeTerminal(1000, 10);
        assertThat(jdbc.queryForObject("select count(*) from inventory_writeback where ncr_no = ?", Integer.class, ncr)).isZero();
    }

    /** 执行中的任务状态为 RUNNING：阻塞关闭、不可重试 / 结案，作废时追加 VOID 而非取消 HOLD。 */
    @Test void runningTaskIsVisibleAndBlocksClose() {
        String ncr = open(rid(), "1");
        java.util.concurrent.atomic.AtomicReference<WritebackStatus> seen = new java.util.concurrent.atomic.AtomicReference<>();
        inventory.during(c -> seen.set(task(ncr, Action.HOLD).status()));
        worker.runBatch();
        assertThat(seen.get()).isEqualTo(WritebackStatus.RUNNING);
        assertThat(task(ncr, Action.HOLD).status()).isEqualTo(WritebackStatus.DONE);
    }

    /** 鉴权失败（库存 403 / 刷新令牌后仍 401）：FAILED 不重试，计 auth_failure；VOID 走 releases，operation=release。 */
    @Test void authFailureFailsAndIsCounted() {
        String r = rid();
        String ncr = open(r, "1");
        worker.runBatch();
        inventory.during(c -> { });
        reverse(r);
        inventory.script("qc:" + ncr + ":void", new Rejected("HTTP 403 access-denied", true, InventoryQcHoldClient.Signal.AUTH));
        double before = registry.get("mes.quality.inventory.auth.failure").tag("operation", "release").counter().count();
        worker.runBatch();
        assertThat(task(ncr, Action.VOID).status()).isEqualTo(WritebackStatus.FAILED);
        assertThat(task(ncr, Action.VOID).attempts()).isEqualTo(1);
        assertThat(registry.get("mes.quality.inventory.auth.failure").tag("operation", "release").counter().count()).isEqualTo(before + 1);
    }

    @Test void allResultSeriesArePreRegistered() {
        for (String op : QualityMetrics.INVENTORY_AUTH_OPERATIONS)
            assertThat(registry.find("mes.quality.inventory.auth.failure").tag("operation", op).counter()).as(op).isNotNull();
        for (String op : QualityMetrics.INVENTORY_CONTRACT_OPERATIONS)
            assertThat(registry.find("mes.quality.inventory.contract.mismatch").tag("operation", op).counter()).as(op).isNotNull();
        for (String result : QualityMetrics.WRITEBACK_RESULTS)
            assertThat(registry.find("mes.quality.writeback").tag("result", result).counter()).as(result).isNotNull();
        assertThat(registry.find("mes.quality.writeback.pending").gauge()).isNotNull();
    }
}
