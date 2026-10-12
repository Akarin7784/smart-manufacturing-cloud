package com.smartmanufacturing.workorder;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationInfo;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import java.util.Arrays;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * 真实 MySQL 8.4 迁移校验和：flyway_schema_history 中已执行的 V2 校验和必须等于当前 V2 文件计算出的校验和。
 * 防止 V2 已在某个环境执行后又被修改（Flyway validate 会失败，或更糟：在未校验的环境中静默漂移）。
 * 期望值取自 Flyway 对类路径中最终 V2 文件的解析结果（MigrationInfo#getChecksum 对待执行与已执行迁移分别取自文件与历史表）。
 */
@Testcontainers
@SpringBootTest
@ActiveProfiles("test")
class V2ChecksumMySqlIT {
    @Container @ServiceConnection
    static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.4");

    @Autowired Flyway flyway;
    @Autowired JdbcTemplate jdbc;
    @MockitoBean MasterdataClient masterdata;
    @MockitoBean DeviceClient devices;
    @MockitoBean org.springframework.security.oauth2.jwt.JwtDecoder decoder;

    @Test void appliedV2ChecksumMatchesFile() {
        Integer applied = jdbc.queryForObject("select checksum from flyway_schema_history where version = '2' and success = 1", Integer.class);
        // 用一个只读、不连库历史的 Flyway 实例从类路径文件计算校验和：pending 状态下 checksum 来自文件本身
        Flyway fileOnly = Flyway.configure().dataSource(MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword())
            .locations("classpath:db/migration").table("checksum_probe_history").load();
        MigrationInfo v2 = Arrays.stream(fileOnly.info().pending()).filter(i -> "2".equals(i.getVersion().getVersion())).findFirst().orElseThrow();
        assertThat(applied).as("flyway_schema_history 中 V2 的校验和").isEqualTo(v2.getChecksum());
        assertThat(flyway.validateWithResult().validationSuccessful).isTrue();
    }
}
