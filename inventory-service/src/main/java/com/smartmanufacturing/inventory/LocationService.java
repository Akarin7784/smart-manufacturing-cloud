package com.smartmanufacturing.inventory;

import com.smartmanufacturing.inventory.InventoryDtos.CreateLocation;
import com.smartmanufacturing.inventory.InventoryDtos.Location;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import java.util.List;

/** 库位维护：库位只新增、不物理删除（历史库存与流水需要引用）。 */
@Service
public class LocationService {
    private final JdbcTemplate jdbc;
    public LocationService(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    /** 新建库位；编码重复返回 400 validation-error（库位编码由用户指定，重复属于输入错误）。 */
    public Location create(CreateLocation r) {
        Location location = new Location(r.code().strip(), r.warehouseCode().strip(), r.name().strip(), true);
        try {
            jdbc.update("insert into warehouse_location (code, warehouse_code, name, enabled) values (?, ?, ?, ?)",
                location.code(), location.warehouseCode(), location.name(), true);
        } catch (DuplicateKeyException e) {
            throw ProblemException.validation("库位编码已存在：" + location.code());
        }
        return location;
    }

    public List<Location> list() {
        return jdbc.query("select code, warehouse_code, name, enabled from warehouse_location order by code",
            (rs, n) -> new Location(rs.getString(1), rs.getString(2), rs.getString(3), rs.getBoolean(4)));
    }

    /** 入库前校验库位存在且启用；不存在或停用属于请求错误（400），而非资源不存在（404，路径资源才用 404）。 */
    void requireEnabled(String code) {
        List<Boolean> rows = jdbc.query("select enabled from warehouse_location where code = ?", (rs, n) -> rs.getBoolean(1), code);
        if (rows.isEmpty()) throw ProblemException.validation("库位不存在：" + code);
        if (!rows.get(0)) throw ProblemException.validation("库位已停用：" + code);
    }
}
