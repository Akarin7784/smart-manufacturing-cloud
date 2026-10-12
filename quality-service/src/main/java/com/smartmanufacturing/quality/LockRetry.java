package com.smartmanufacturing.quality;
import org.springframework.dao.PessimisticLockingFailureException;
import java.util.function.Supplier;

/**
 * 死锁/锁等待超时重试：质检写入每笔只锁一行 NCR，但 MySQL 的间隙锁（插入处置行、幂等记录时）仍可能产生死锁，
 * 被选为牺牲者的事务已整体回滚，可以安全地整笔重试。最多 3 次，仍失败则向上抛出（返回 500，由调用方按幂等键重试）。
 */
final class LockRetry {
    private static final int MAX_ATTEMPTS = 3;
    private LockRetry() {}

    static <T> T run(Supplier<T> work) {
        for (int attempt = 1; ; attempt++) {
            try {
                return work.get();
            } catch (PessimisticLockingFailureException e) {
                if (attempt >= MAX_ATTEMPTS) throw e;
            }
        }
    }
}
