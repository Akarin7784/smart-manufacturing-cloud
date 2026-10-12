package com.smartmanufacturing.masterdata;
import com.smartmanufacturing.api.masterdata.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;
import java.util.*;

/** 物料表数据访问；全部使用参数绑定，不拼接业务输入。 */
@Repository
class MaterialRepository {
    private static final String COLUMNS = "code, name, type, uom, trace_mode, source, active, version";
    private static final RowMapper<Material> MAPPER = (rs, n) -> new Material(rs.getString(1), rs.getString(2),
        MaterialType.valueOf(rs.getString(3)), rs.getString(4), TraceMode.valueOf(rs.getString(5)),
        MaterialSource.valueOf(rs.getString(6)), rs.getBoolean(7), rs.getLong(8));
    private final JdbcTemplate jdbc;
    MaterialRepository(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    Optional<Material> find(String code) {
        return jdbc.query("select " + COLUMNS + " from material where code = ?", MAPPER, code).stream().findFirst();
    }
    /** 按可选类型过滤并分页，按编码排序保证结果稳定。 */
    List<Material> list(MaterialType type, int offset, int limit) {
        return type == null
            ? jdbc.query("select " + COLUMNS + " from material order by code limit ? offset ?", MAPPER, limit, offset)
            : jdbc.query("select " + COLUMNS + " from material where type = ? order by code limit ? offset ?", MAPPER, type.name(), limit, offset);
    }
    /** 返回已存在的编码集合，用于 BOM 发布前批量校验组件。 */
    Set<String> existingCodes(Collection<String> codes) {
        Set<String> found = new HashSet<>();
        for (String code : codes) if (find(code).isPresent()) found.add(code);
        return found;
    }
    void insert(CreateMaterial m) {
        jdbc.update("insert into material (code, name, type, uom, trace_mode, source, active, version) values (?, ?, ?, ?, ?, ?, true, 0)",
            m.code(), m.name(), m.type().name(), m.uom(), m.traceMode().name(), m.source().name());
    }
    /** 乐观锁更新：返回受影响行数，0 表示版本已变化或记录不存在。 */
    int update(String code, UpdateMaterial m) {
        return jdbc.update("update material set name = ?, uom = ?, trace_mode = ?, active = ?, version = version + 1 where code = ? and version = ?",
            m.name(), m.uom(), m.traceMode().name(), m.active(), code, m.expectedVersion());
    }
}
