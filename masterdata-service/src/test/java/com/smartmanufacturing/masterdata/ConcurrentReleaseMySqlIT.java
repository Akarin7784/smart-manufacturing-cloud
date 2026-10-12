package com.smartmanufacturing.masterdata;
import com.smartmanufacturing.api.masterdata.*;
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
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.*;
import java.util.concurrent.*;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * 真实 MySQL 8.4 并发发布测试：同一产品的两个不同草稿同时发布时，产品行锁必须保证
 * “同一时刻至多一个版本生效”——不得出现两个 effective_to 为空的 RELEASED 版本，生效区间不得重叠。
 * H2 的锁语义与 MySQL 不同，因此只在 *IT 中验证；多轮重复以提高命中竞态窗口的概率。
 */
@Testcontainers
@SpringBootTest
@ActiveProfiles("test")
class ConcurrentReleaseMySqlIT {
    @Container @ServiceConnection
    static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.4");
    private static final int ROUNDS = 15;

    @Autowired BomService boms;
    @Autowired RoutingService routings;
    @Autowired MaterialService materials;
    @Autowired WorkCenterService workCenters;
    @Autowired JdbcTemplate jdbc;
    @MockitoBean org.springframework.security.oauth2.jwt.JwtDecoder decoder;

    /** 表结构必须来自 Flyway V1（含 current_flag 唯一约束），防止回退到 spring.sql.init 而使迁移脚本未经真实 MySQL 验证。 */
    @Test
    void schemaComesFromFlywayV1() {
        assertThat(jdbc.queryForObject(
            "select count(*) from flyway_schema_history where version = '1' and success = 1", Integer.class)).isEqualTo(1);
        // 按索引名去重计数：复合唯一索引每列一行，直接 count(*) 会掩盖其中一个索引丢失的情况。
        assertThat(jdbc.queryForObject("select count(distinct index_name) from information_schema.statistics where table_schema = database()"
            + " and index_name in ('uk_bom_header_current', 'uk_routing_header_current')", Integer.class)).isEqualTo(2);
    }

    /** 两个线程在栅栏处同时发布，收集成功/失败，任何非业务异常都会让测试失败。 */
    private static List<Object> race(Callable<?> a, Callable<?> b) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(2);
        CyclicBarrier barrier = new CyclicBarrier(2);
        try {
            List<Future<Object>> fs = new ArrayList<>();
            for (Callable<?> c : List.of(a, b)) fs.add(pool.submit(() -> { barrier.await(); try { return c.call(); } catch (com.smartmanufacturing.common.BusinessException e) { return e.code(); } }));
            List<Object> out = new ArrayList<>();
            for (Future<Object> f : fs) out.add(f.get(30, TimeUnit.SECONDS));
            return out;
        } finally { pool.shutdownNow(); }
    }

    /** 断言产品的已发布版本生效区间互不重叠，且至多一个开放区间。 */
    private void assertSingleEffective(String table, String product) {
        List<Map<String, Object>> rows = jdbc.queryForList("select effective_from, effective_to from " + table
            + " where product_code = ? and status = 'RELEASED' order by effective_from", product);
        assertThat(rows.stream().filter(r -> r.get("effective_to") == null).count()).as(product + " 开放区间数").isEqualTo(1);
        for (int i = 1; i < rows.size(); i++)
            assertThat(rows.get(i - 1).get("effective_to")).as(product + " 区间衔接").isEqualTo(rows.get(i).get("effective_from"));
    }

    @Test void concurrentBomReleaseKeepsSingleEffectiveVersion() throws Exception {
        materials.create(new CreateMaterial("IT-RM", "原材料", MaterialType.RM, "件", TraceMode.LOT, MaterialSource.BUY));
        for (int r = 0; r < ROUNDS; r++) {
            String p = "IT-BOM-" + r;
            materials.create(new CreateMaterial(p, p, MaterialType.PART, "件", TraceMode.LOT, MaterialSource.MAKE));
            List<BomLine> lines = List.of(new BomLine(10, "IT-RM", BigDecimal.ONE, "件", BigDecimal.ZERO, IssueMode.PICK, null, false));
            boms.create(new SaveBom(p, "A01", LocalDate.of(2026, 10, 1), lines));
            boms.release(p, "A01");
            boms.create(new SaveBom(p, "A02", LocalDate.of(2026, 11, 1), lines));
            boms.create(new SaveBom(p, "A03", LocalDate.of(2026, 12, 1), lines));
            race(() -> boms.release(p, "A02"), () -> boms.release(p, "A03"));
            assertSingleEffective("bom_header", p);
        }
    }

    @Test void concurrentRoutingReleaseKeepsSingleEffectiveVersion() throws Exception {
        workCenters.create(new CreateWorkCenter("WC-IT", "并发测试", "W09", false));
        for (int r = 0; r < ROUNDS; r++) {
            String p = "IT-RT-" + r;
            materials.create(new CreateMaterial(p, p, MaterialType.PART, "件", TraceMode.LOT, MaterialSource.MAKE));
            List<Operation> ops = List.of(new Operation("OP10", "加工", "WC-IT", 0, 60, true, false, List.of()));
            routings.create(new SaveRouting(p, "R01", LocalDate.of(2026, 10, 1), ops));
            routings.release(p, "R01");
            routings.create(new SaveRouting(p, "R02", LocalDate.of(2026, 11, 1), ops));
            routings.create(new SaveRouting(p, "R03", LocalDate.of(2026, 12, 1), ops));
            race(() -> routings.release(p, "R02"), () -> routings.release(p, "R03"));
            assertSingleEffective("routing_header", p);
        }
    }
}
