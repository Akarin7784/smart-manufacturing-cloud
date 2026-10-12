package com.smartmanufacturing.workorder;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;

/**
 * 写库时间工具。outbox_event、wo_reservation_release_task 等表的时间列是 TIMESTAMP（秒精度），
 * MySQL 写入时对小数秒四舍五入（最多 +0.5 秒），会让“刚写入、同一秒内轮询”的记录被判为未到期（与 #47 同类问题）。
 * 写入与到期比较统一截断到秒，保证同一秒内写入的记录立即可见、可投递。
 */
final class DbTime {
    private DbTime() {}
    /** 截断到秒后的 Timestamp。 */
    static Timestamp ts(Instant instant) { return Timestamp.from(instant.truncatedTo(ChronoUnit.SECONDS)); }
}
