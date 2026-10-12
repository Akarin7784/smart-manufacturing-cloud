package com.smartmanufacturing.workorder;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * 回归：秒精度 TIMESTAMP 列会对小数秒四舍五入（.700 → 下一秒）。同一秒内写入并轮询的记录必须判为到期。
 * 固定时钟、不 sleep；H2 的 TIMESTAMP(0) 与 MySQL TIMESTAMP 同样四舍五入。
 */
class DbTimeTest {
    private final Clock clock = Clock.fixed(Instant.parse("2026-10-12T14:00:00.700Z"), ZoneOffset.UTC);

    private JdbcTemplate db() {
        JdbcTemplate jdbc = new JdbcTemplate(new DriverManagerDataSource("jdbc:h2:mem:" + UUID.randomUUID() + ";MODE=MySQL;DB_CLOSE_DELAY=-1"));
        jdbc.execute("create table t (id int primary key, next_attempt_at timestamp(0))");
        return jdbc;
    }

    private int due(JdbcTemplate jdbc, Timestamp now) {
        return jdbc.queryForObject("select count(*) from t where next_attempt_at <= ?", Integer.class, now);
    }

    /** 截断到秒后写入与比较：同一秒内立即到期。 */
    @Test void writtenAndPolledInSameSecondIsDue() {
        JdbcTemplate jdbc = db();
        jdbc.update("insert into t values (1, ?)", DbTime.ts(clock.instant()));
        assertThat(due(jdbc, DbTime.ts(clock.instant()))).isEqualTo(1);
    }

    /** 对照：不截断时列被进位到下一秒，同一秒内轮询判为未到期（即本修复针对的缺陷）。 */
    @Test void withoutTruncationRoundingMakesItNotDue() {
        JdbcTemplate jdbc = db();
        jdbc.update("insert into t values (1, ?)", Timestamp.from(clock.instant()));
        assertThat(due(jdbc, Timestamp.from(clock.instant()))).isZero();
    }
}
