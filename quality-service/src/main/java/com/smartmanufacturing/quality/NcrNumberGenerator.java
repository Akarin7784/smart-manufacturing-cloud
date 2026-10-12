package com.smartmanufacturing.quality;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;

/**
 * NCR 编号生成：{@code NCR-yyyyMMdd-####}，按业务日期（默认 Asia/Shanghai，可配置 quality.ncr.number-zone）每日从 0001 起流水，
 * 超过 9999 自然变为 5 位（契约 pattern 为 \d{4,}）。
 * <p>
 * 流水号存于 ncr_daily_seq，以 UPDATE 取得当日行锁再递增，锁随调用方事务提交释放：多实例并发不重号，
 * 回滚则号段一并回滚（不留空号）。代价是同一天的 NCR 创建串行化，报工不良的频率下可以接受。
 */
@Component
public class NcrNumberGenerator {
    private static final DateTimeFormatter DAY = DateTimeFormatter.BASIC_ISO_DATE;
    private final JdbcTemplate jdbc;
    private final Clock clock;
    private final ZoneId zone;

    public NcrNumberGenerator(JdbcTemplate jdbc, Clock clock, @Value("${quality.ncr.number-zone:Asia/Shanghai}") String zone) {
        this.jdbc = jdbc; this.clock = clock; this.zone = ZoneId.of(zone);
    }

    /** 在调用方事务内分配下一个编号。 */
    @Transactional(propagation = Propagation.MANDATORY)
    public String next() {
        String day = LocalDate.ofInstant(clock.instant(), zone).format(DAY);
        if (jdbc.update("update ncr_daily_seq set last_no = last_no + 1 where seq_date = ?", day) == 0) {
            try {
                jdbc.update("insert into ncr_daily_seq (seq_date, last_no) values (?, 1)", day);
            } catch (DuplicateKeyException race) {
                // 另一事务同时插入了当天的首行：等其提交后改为递增。
                jdbc.update("update ncr_daily_seq set last_no = last_no + 1 where seq_date = ?", day);
            }
        }
        Integer no = jdbc.queryForObject("select last_no from ncr_daily_seq where seq_date = ?", Integer.class, day);
        return String.format("NCR-%s-%04d", day, no);
    }
}
