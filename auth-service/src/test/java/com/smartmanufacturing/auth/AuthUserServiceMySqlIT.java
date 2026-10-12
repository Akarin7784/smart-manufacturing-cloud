package com.smartmanufacturing.auth;

import com.smartmanufacturing.common.BusinessException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.*;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 认证用户服务的真实 MySQL 8.4 集成测试（Testcontainers）。
 * 保护 H2 无法可靠验证的行为：SELECT ... FOR UPDATE 行锁下并发失败计数不丢失、唯一键冲突翻译为 409。
 * 表结构由 Flyway 执行服务自身 db/migration/V1__init.sql 创建（与生产同一套迁移脚本，test profile 不加载 db/demo）。
 * 不使用 disabledWithoutDocker：Docker 不可用时必须失败而非静默跳过，避免 Linux CI 假绿；
 * 无法运行 Linux 容器的环境（Windows CI、本地无 Docker）请显式使用 -DskipITs 跳过。
 */
@Testcontainers
@SpringBootTest
@ActiveProfiles("test")
class AuthUserServiceMySqlIT {
    /** 与生产一致的 MySQL 8.4；@ServiceConnection 覆盖 test profile 中的 H2 数据源。 */
    @Container @ServiceConnection
    static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.4");

    /** 签名密钥写入临时目录，避免污染工作区。 */
    @DynamicPropertySource static void props(DynamicPropertyRegistry registry) throws Exception {
        Path dir = Files.createTempDirectory("auth-it-");
        registry.add("auth.private-key-path", () -> dir.resolve("signing-key.json").toString());
    }

    @Autowired AuthUserService users;
    @Autowired JdbcTemplate jdbc;
    /** 用于在独立事务中持有行锁，制造确定性的并发竞争。 */
    @Autowired org.springframework.transaction.support.TransactionTemplate transactions;

    /** 每个用例前清理非管理员账号，保证用例之间互不影响。 */
    @BeforeEach void clean() { jdbc.update("delete from auth_user where username like 'it-%'"); }

    /** 确认数据源确实是 MySQL，防止配置回退到 H2 导致测试名不副实。 */
    @Test @DisplayName("数据源为真实 MySQL 8.4")
    void runsOnRealMySql() {
        assertThat(jdbc.queryForObject("select version()", String.class)).startsWith("8.4");
    }

    /** 表结构必须来自 Flyway 迁移（V1 成功执行），防止回退到 spring.sql.init 而使迁移脚本未经真实 MySQL 验证。 */
    @Test @DisplayName("表结构由 Flyway V1 创建")
    void schemaComesFromFlywayV1() {
        assertThat(jdbc.queryForObject(
            "select count(*) from flyway_schema_history where version = '1' and success = 1", Integer.class)).isEqualTo(1);
    }

    /**
     * 确定性地制造“读-改-写”竞争：外部事务先持有该行的排他锁，再同时放行多个错误密码登录。
     * 有 FOR UPDATE 时，各登录在加锁读处阻塞，锁释放后依次读到最新计数，最终计数 = 线程数；
     * 若去掉 FOR UPDATE，各登录以快照读同时读到 0，再排队写入同一个值，计数会丢失（最终为 1），测试必然失败。
     */
    @Test @DisplayName("并发失败登录计数不丢失（FOR UPDATE）")
    void concurrentFailuresAreCountedExactly() throws Exception {
        users.create(new CreateUserRequest("it-operator", "correct-password-2026", Role.OPERATOR));
        int threads = 4; // 小于锁定阈值 5，便于直接断言精确计数
        ExecutorService pool = Executors.newFixedThreadPool(threads + 1);
        CountDownLatch lockHeld = new CountDownLatch(1);
        CountDownLatch start = new CountDownLatch(1);
        try {
            // 持锁事务：锁住账号行，等待登录线程全部进入数据库访问后再提交释放。
            Future<?> holder = pool.submit(() -> transactions.executeWithoutResult(status -> {
                jdbc.queryForObject("select id from auth_user where username = 'it-operator' for update", String.class);
                lockHeld.countDown();
                try { Thread.sleep(3000); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            }));
            assertThat(lockHeld.await(30, TimeUnit.SECONDS)).isTrue();
            List<Future<?>> futures = new ArrayList<>();
            for (int i = 0; i < threads; i++) {
                futures.add(pool.submit(() -> {
                    start.await(); // 所有线程同时放行，最大化竞争
                    assertThatThrownBy(() -> users.authenticate(new LoginRequest("it-operator", "wrong-password-xx")))
                        .isInstanceOf(BusinessException.class);
                    return null;
                }));
            }
            start.countDown();
            holder.get(60, TimeUnit.SECONDS);
            for (Future<?> f : futures) f.get(60, TimeUnit.SECONDS);
        } finally {
            pool.shutdownNow();
        }
        Integer attempts = jdbc.queryForObject("select failed_attempts from auth_user where username = 'it-operator'", Integer.class);
        assertThat(attempts).isEqualTo(threads);
    }

    /** MySQL 唯一索引冲突必须翻译为 USERNAME_EXISTS/409，而不是 500。 */
    @Test @DisplayName("重复用户名返回 USERNAME_EXISTS")
    void duplicateUsernameIsConflict() {
        users.create(new CreateUserRequest("it-dup", "correct-password-2026", Role.VIEWER));
        assertThatThrownBy(() -> users.create(new CreateUserRequest("it-dup", "another-password-2026", Role.VIEWER)))
            .isInstanceOfSatisfying(BusinessException.class, e -> {
                assertThat(e.code()).isEqualTo("USERNAME_EXISTS");
                assertThat(e.status().value()).isEqualTo(409);
            });
    }
}
