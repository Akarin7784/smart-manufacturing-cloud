package com.smartmanufacturing.workorder;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;

/**
 * 数据库时间写入的统一入口：workorder 的时间列（V5 起）均为 TIMESTAMP(3)，只保存到毫秒。
 * 写入前在 Java 侧先截断到毫秒，而不是交给 MySQL 处理：MySQL 对多余的小数秒默认四舍五入（例如 .0006 存成 .001），
 * 会让刚写入的 occurred_at / next_attempt_at 比同一毫秒内读取的“当前时间”还晚，导致同一毫秒内拉批时漏掉已到期的记录。
 * 与 quality、inventory 的 OutboxWriter（#47）规则一致：存到毫秒，写之前截断到毫秒。
 */
final class DbTimes {
    private DbTimes() {}

    /** 截断到毫秒；空值原样返回。 */
    static Instant millis(Instant instant) {
        return instant == null ? null : instant.truncatedTo(ChronoUnit.MILLIS);
    }

    /** 截断到毫秒后转为 JDBC 参数；空值原样返回。 */
    static Timestamp ts(Instant instant) {
        return instant == null ? null : Timestamp.from(millis(instant));
    }
}
