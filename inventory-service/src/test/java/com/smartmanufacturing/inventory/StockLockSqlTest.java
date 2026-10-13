package com.smartmanufacturing.inventory;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * 加锁 SQL 形态（#57）：FOR UPDATE 只能按主键 id 加锁并按 id 升序，不能带物料、库位等范围条件，
 * 否则 MySQL 范围扫描会锁住其他物料的行，交叉预留时死锁。
 */
class StockLockSqlTest {
    @Test void lockStatementLocksOnlyByPrimaryKeyInIdOrder() {
        String sql = StockService.lockByIdSql(3).toLowerCase();
        assertThat(sql).endsWith("from stock_on_hand where id in (?,?,?) order by id for update");
        // where 子句只有 id 条件：不含物料、库位、批次过滤。
        String where = sql.substring(sql.indexOf(" where "));
        assertThat(where).doesNotContain("material_code").doesNotContain("location_code").doesNotContain("lot_no").doesNotContain("<>");
    }

    @Test void placeholderCountMatchesIds() {
        assertThat(StockService.lockByIdSql(1)).contains("id in (?)");
    }
}
