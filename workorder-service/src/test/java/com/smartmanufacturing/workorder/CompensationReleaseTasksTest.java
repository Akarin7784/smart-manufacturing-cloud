package com.smartmanufacturing.workorder;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionTemplate;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.*;
import static org.assertj.core.api.Assertions.*;

/**
 * 补偿释放任务写入的幂等：同一键重复 / 并发写入只留一条任务，撞唯一键按“任务已存在”返回，不抛到 ERROR 路径；
 * 其他完整性错误照常抛出。用 H2（MySQL 模式）建与 V4 相同唯一约束的表，不依赖容器。
 */
class CompensationReleaseTasksTest {
    private JdbcTemplate jdbc;
    private TransactionTemplate tx;

    @BeforeEach void setUp() {
        DriverManagerDataSource ds = new DriverManagerDataSource("jdbc:h2:mem:" + UUID.randomUUID() + ";MODE=MySQL;DB_CLOSE_DELAY=-1");
        jdbc = new JdbcTemplate(ds);
        tx = new TransactionTemplate(new DataSourceTransactionManager(ds));
        jdbc.execute("""
            create table wo_reservation_release_task (task_id varchar(36) primary key, order_id varchar(64) not null,
              reservation_id varchar(64) not null, reason varchar(32) not null, idempotency_key varchar(128) not null,
              status varchar(16) not null, attempts int not null, created_at timestamp not null,
              constraint uk_wo_reservation_release_task_key unique (idempotency_key))""");
    }

    private int tasks(String key) {
        return jdbc.queryForObject("select count(*) from wo_reservation_release_task where idempotency_key = ?", Integer.class, key);
    }

    /** 重复写同一键：第二次返回 false（已存在），仍只有一条任务。 */
    @Test void repeatedInsertKeepsOneTask() {
        String key = WorkOrderCancellation.releaseKey("WO-1", "R-1");
        assertThat(CompensationReleaseTasks.insert(jdbc, tx, "WO-1", "R-1", key, Instant.now())).isTrue();
        assertThat(CompensationReleaseTasks.insert(jdbc, tx, "WO-1", "R-1", key, Instant.now())).isFalse();
        assertThat(tasks(key)).isEqualTo(1);
    }

    /** 并发写同一键：恰好一个写入成功，其余都按已存在返回，没有异常。 */
    @Test void concurrentInsertKeepsOneTaskWithoutError() throws Exception {
        String key = WorkOrderCancellation.releaseKey("WO-2", "R-2");
        int n = 8;
        ExecutorService pool = Executors.newFixedThreadPool(n);
        CyclicBarrier barrier = new CyclicBarrier(n);
        List<Future<Boolean>> results = new ArrayList<>();
        for (int i = 0; i < n; i++)
            results.add(pool.submit(() -> { barrier.await(); return CompensationReleaseTasks.insert(jdbc, tx, "WO-2", "R-2", key, Instant.now()); }));
        int created = 0;
        for (Future<Boolean> f : results) if (f.get(30, TimeUnit.SECONDS)) created++;
        pool.shutdown();
        assertThat(created).isEqualTo(1);
        assertThat(tasks(key)).isEqualTo(1);
    }

    /** 唯一键冲突的识别：DuplicateKeyException、根因 MySQL 1062 算；其他完整性错误（如非空约束）不算。 */
    @Test void onlyUniqueKeyViolationIsTreatedAsExisting() {
        assertThat(CompensationReleaseTasks.isTaskKeyDuplicate(new DuplicateKeyException("dup"))).isTrue();
        assertThat(CompensationReleaseTasks.isTaskKeyDuplicate(new DataIntegrityViolationException("x",
            new SQLException("Duplicate entry 'cancel-WO-1-R-1'", "23000", 1062)))).isTrue();
        assertThat(CompensationReleaseTasks.isTaskKeyDuplicate(new DataIntegrityViolationException("x",
            new SQLException("Column 'order_id' cannot be null", "23000", 1048)))).isFalse();
    }
}
