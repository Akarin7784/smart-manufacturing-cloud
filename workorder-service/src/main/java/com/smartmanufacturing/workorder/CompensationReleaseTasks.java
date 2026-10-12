package com.smartmanufacturing.workorder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Locale;
import java.util.UUID;

/**
 * 补偿释放失败后写入释放任务（短事务）。任务以 idempotency_key（cancel-{工单号}-{预留单号}）唯一约束去重：
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

    /**
     * 写入一条 PENDING 释放任务。
     *
     * @return true = 本次新写入；false = 同一幂等键的任务已存在（含并发撞唯一键的情况）
     */
    static boolean insert(JdbcTemplate jdbc, TransactionTemplate tx, String orderId, String reservationId, String key, Instant now) {
        try {
            Integer rows = tx.execute(s -> jdbc.update(
                "insert into wo_reservation_release_task (task_id, order_id, reservation_id, reason, idempotency_key, status, attempts, created_at)"
                    + " select ?, ?, ?, ?, ?, 'PENDING', 0, ? from dual where not exists (select 1 from wo_reservation_release_task where idempotency_key = ?)",
                UUID.randomUUID().toString(), orderId, reservationId, MaterialReservationPort.ReleaseReason.COMPENSATION.name(),
                key, DbTime.ts(now), key));
            if (rows != null && rows == 1) return true;
            LOG.debug("补偿释放任务已存在，跳过写入：{}", key);
            return false;
        } catch (DataIntegrityViolationException e) {
            if (!isTaskKeyDuplicate(e)) throw e;
            // 并发写同一个键：另一请求已写入任务，本次视为成功
            LOG.debug("补偿释放任务已由并发请求写入（唯一键冲突），视为已存在：{}", key);
            return false;
        }
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
