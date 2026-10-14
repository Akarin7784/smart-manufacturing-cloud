package com.smartmanufacturing.workorder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;
import java.sql.SQLException;
import java.time.Instant;
import java.util.Locale;
import java.util.UUID;

/**
 * 写入库存预留释放任务（补偿释放走独立短事务，工单关闭走调用方事务）。任务以 idempotency_key 唯一约束去重：
 * <ul>
 *   <li>insert … where not exists 挡住大多数重复写入；</li>
 *   <li>两个请求并发写同一个键时，后提交者仍会撞唯一键。此时任务其实已经存在，结果与期望一致，
 *       按“任务已存在”处理，只记 DEBUG，不进入 ERROR 路径；</li>
 *   <li>其他数据库失败（连接、其他约束等）原样抛出，由调用方记 ERROR。</li>
 * </ul>
 */
final class CompensationReleaseTasks {
    private static final Logger LOG = LoggerFactory.getLogger(CompensationReleaseTasks.class);
    /** V4 迁移中 idempotency_key 唯一约束的名称。 */
    static final String UNIQUE_KEY_CONSTRAINT = "uk_wo_reservation_release_task_key";
    /** MySQL 唯一键冲突的错误码（ER_DUP_ENTRY）。 */
    private static final int MYSQL_DUP_ENTRY = 1062;

    private CompensationReleaseTasks() {}

    /** 补偿释放（reason=COMPENSATION）：在独立短事务中写入任务，见 {@link #insert(JdbcTemplate, TransactionTemplate, String, String, MaterialReservationPort.ReleaseReason, String, Instant)}。 */
    static boolean insert(JdbcTemplate jdbc, TransactionTemplate tx, String orderId, String reservationId, String key, Instant now) {
        return insert(jdbc, tx, orderId, reservationId, MaterialReservationPort.ReleaseReason.COMPENSATION, key, now);
    }

    /**
     * 在独立短事务（tx）中写入一条 PENDING 释放任务，并发撞唯一键按“已存在”处理。
     *
     * @return true = 本次新写入；false = 同一幂等键的任务已存在（含并发撞唯一键的情况）
     */
    static boolean insert(JdbcTemplate jdbc, TransactionTemplate tx, String orderId, String reservationId,
                          MaterialReservationPort.ReleaseReason reason, String key, Instant now) {
        try {
            Boolean created = tx.execute(s -> insertInCurrentTx(jdbc, orderId, reservationId, reason, key, now));
            return Boolean.TRUE.equals(created);
        } catch (DataIntegrityViolationException e) {
            if (!isTaskKeyDuplicate(e)) throw e;
            // 并发写同一个键：另一请求已写入任务，本次视为成功
            LOG.debug("释放任务已由并发请求写入（唯一键冲突），视为已存在：{}", key);
            return false;
        }
    }

    /**
     * 在调用方当前事务内写入一条 PENDING 释放任务（如工单关闭 closeInTx，需与状态变更同一事务提交）。
     * 只用 insert … where not exists 去重；调用方须已持有工单行锁，同一工单的并发写入已被串行化。
     * 这里不捕获唯一键冲突：在外层事务中吞掉异常会让事务处于不确定状态，冲突时直接抛出、整体回滚更安全。
     *
     * @return true = 本次新写入；false = 同一幂等键的任务已存在
     */
    static boolean insertInCurrentTx(JdbcTemplate jdbc, String orderId, String reservationId,
                                     MaterialReservationPort.ReleaseReason reason, String key, Instant now) {
        int rows = jdbc.update(
            "insert into wo_reservation_release_task (task_id, order_id, reservation_id, reason, idempotency_key, status, attempts, created_at)"
                + " select ?, ?, ?, ?, ?, 'PENDING', 0, ? from dual where not exists (select 1 from wo_reservation_release_task where idempotency_key = ?)",
            UUID.randomUUID().toString(), orderId, reservationId, reason.name(),
            key, DbTimes.ts(now), key); // 与 V5 的 TIMESTAMP(3) 对齐，截断到毫秒
        if (rows == 1) return true;
        LOG.debug("释放任务已存在，跳过写入：{}", key);
        return false;
    }

    /**
     * 判断是否为任务幂等键唯一约束冲突。DuplicateKeyException 一定是唯一键冲突（该表只有主键与幂等键两个唯一约束，
     * 主键是随机 UUID，不会冲突）；其他 DataIntegrityViolationException 只有在根因是 MySQL 1062 且指向本约束时才算。
     */
    static boolean isTaskKeyDuplicate(DataIntegrityViolationException e) {
        if (e instanceof DuplicateKeyException) return true;
        for (Throwable t = e; t != null; t = t.getCause()) {
            if (t instanceof SQLException sql && sql.getErrorCode() == MYSQL_DUP_ENTRY) return true;
            String msg = t.getMessage();
            if (msg != null && msg.toLowerCase(Locale.ROOT).contains(UNIQUE_KEY_CONSTRAINT)) return true;
        }
        return false;
    }
}
