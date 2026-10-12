package com.smartmanufacturing.masterdata;
import com.smartmanufacturing.api.masterdata.LifecycleStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import java.time.LocalDate;
import java.util.*;

/**
 * BOM 与工艺路线共用的“版本头”数据访问：两者生命周期规则完全一致，抽出以免重复实现出现分歧。
 * 表名只来自本类内部常量（bom_header / routing_header），不接受外部输入，不存在 SQL 注入面。
 */
final class VersionedHeaderDao {
    /** 版本头视图。 */
    record Header(String productCode, String version, LifecycleStatus status, LocalDate effectiveFrom, LocalDate effectiveTo) {}

    private final JdbcTemplate jdbc;
    private final String table;
    VersionedHeaderDao(JdbcTemplate jdbc, String table) {
        if (!table.equals("bom_header") && !table.equals("routing_header")) throw new IllegalArgumentException(table);
        this.jdbc = jdbc; this.table = table;
    }

    Optional<Header> find(String product, String version) {
        return jdbc.query("select product_code, version, status, effective_from, effective_to from " + table + " where product_code = ? and version = ?",
            (rs, n) -> map(rs), product, version).stream().findFirst();
    }
    List<Header> listByProduct(String product) {
        return jdbc.query("select product_code, version, status, effective_from, effective_to from " + table + " where product_code = ? order by effective_from, version",
            (rs, n) -> map(rs), product);
    }
    /** 指定日期生效的已发布版本：status=RELEASED 且 effective_from ≤ 日期 < effective_to（为空视为无穷）。 */
    Optional<Header> effective(String product, LocalDate date) {
        return jdbc.query("select product_code, version, status, effective_from, effective_to from " + table
                + " where product_code = ? and status = 'RELEASED' and effective_from <= ? and (effective_to is null or effective_to > ?)"
                + " order by effective_from desc", (rs, n) -> map(rs), product, date, date).stream().findFirst();
    }
    /** 当前最新的已发布版本（生效起始日最大），发布新版本时用于截断。 */
    Optional<Header> latestReleased(String product) {
        return jdbc.query("select product_code, version, status, effective_from, effective_to from " + table
            + " where product_code = ? and status = 'RELEASED' order by effective_from desc", (rs, n) -> map(rs), product).stream().findFirst();
    }
    void insertDraft(String product, String version, LocalDate from) {
        jdbc.update("insert into " + table + " (product_code, version, status, effective_from) values (?, ?, 'DRAFT', ?)", product, version, from);
    }
    /** 仅草稿可改生效日；返回 0 表示已非草稿（并发发布）。 */
    int updateDraftFrom(String product, String version, LocalDate from) {
        return jdbc.update("update " + table + " set effective_from = ? where product_code = ? and version = ? and status = 'DRAFT'", from, product, version);
    }
    /** 条件更新实现“草稿→发布”，并发发布同一版本只有一个成功。 */
    int release(String product, String version) {
        return jdbc.update("update " + table + " set status = 'RELEASED' where product_code = ? and version = ? and status = 'DRAFT'", product, version);
    }
    /** 截断上一已发布版本的生效区间，使同一时刻至多一个版本生效。 */
    void truncate(String product, String version, LocalDate to) {
        jdbc.update("update " + table + " set effective_to = ? where product_code = ? and version = ?", to, product, version);
    }
    private static Header map(java.sql.ResultSet rs) throws java.sql.SQLException {
        java.sql.Date to = rs.getDate(5);
        return new Header(rs.getString(1), rs.getString(2), LifecycleStatus.valueOf(rs.getString(3)),
            rs.getDate(4).toLocalDate(), to == null ? null : to.toLocalDate());
    }
}
