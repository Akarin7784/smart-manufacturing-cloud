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
 * 表结构沿用服务自身 schema.sql（不含演示数据）；无 Docker 环境（如 Windows CI）时整类自动跳过。
 */
@Testcontainers(disabledWithoutDocker = true)
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

    /** 每个用例前清理非管理员账号，保证用例之间互不影响。 */
    @BeforeEach void clean() { jdbc.update("delete from auth_user where username like 'it-%'"); }

    /** 确认数据源确实是 MySQL，防止配置回退到 H2 导致测试名不副实。 */
    @Test @DisplayName("数据源为真实 MySQL 8.4")
    void runsOnRealMySql() {
        assertThat(jdbc.queryForObject("select version()", String.class)).startsWith("8.4");
    }

    /** 并发错误密码登录在行锁下串行化，失败次数必须精确累加，不得因丢失更新而少计。 */
    @Test @DisplayName("并发失败登录计数不丢失（FOR UPDATE）")
    void concurrentFailuresAreCountedExactly() throws Exception {
        users.create(new CreateUserRequest("it-operator", "correct-password-2026", Role.OPERATOR));
        int threads = 4; // 小于锁定阈值 5，便于直接断言计数
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<?>> futures = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            futures.add(pool.submit(() -> {
                start.await();
                assertThatThrownBy(() -> users.authenticate(new LoginRequest("it-operator", "wrong-password-xx")))
                    .isInstanceOf(BusinessException.class);
                return null;
            }));
        }
        start.countDown();
        for (Future<?> f : futures) f.get(60, TimeUnit.SECONDS);
        pool.shutdown();
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
