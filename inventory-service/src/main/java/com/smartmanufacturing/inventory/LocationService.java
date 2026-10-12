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

    /** 通用入库校验：库位存在、启用且不是系统库位（QC-HOLD 只能经质检隔离接口变动）。 */
    void requireGeneral(String code) {
        requireEnabled(code);
        requireSystemExcluded(code);
    }

    /** 通用出库校验：系统库位返回 400 validation-error；库位不存在时不在此报错，按余额 0 处理（保持原出库语义）。 */
    void requireSystemExcluded(String code) {
        List<Boolean> rows = jdbc.query("select system_location from warehouse_location where code = ?", (rs, n) -> rs.getBoolean(1), code);
        if (!rows.isEmpty() && rows.get(0)) throw ProblemException.validation("系统库位 " + code + " 不允许通用出入库，请使用质检隔离接口");
    }

    /** 入库前校验库位存在且启用；不存在或停用属于请求错误（400），而非资源不存在（404，路径资源才用 404）。 */
    void requireEnabled(String code) {
        List<Boolean> rows = jdbc.query("select enabled from warehouse_location where code = ?", (rs, n) -> rs.getBoolean(1), code);
        if (rows.isEmpty()) throw ProblemException.validation("库位不存在：" + code);
        if (!rows.get(0)) throw ProblemException.validation("库位已停用：" + code);
    }
}
