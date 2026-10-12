package com.smartmanufacturing.masterdata;
import com.smartmanufacturing.api.masterdata.*;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.util.*;

/**
 * 工厂模型服务：工作中心与工位。数据量小且结构简单，数据访问直接放在本服务内，
 * 但仍统一使用参数绑定。设备实例不在此维护，由 device-service 按工位编码绑定。
 */
@Service
public class WorkCenterService {
    private final JdbcTemplate jdbc;
    WorkCenterService(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    /** 列出全部工作中心及工位（演示工厂规模下无需分页）。 */
    @Transactional(readOnly = true)
    public List<WorkCenter> list() {
        Map<String, List<Station>> stations = new HashMap<>();
        jdbc.query("select code, name, work_center_code from station order by code", rs -> {
            stations.computeIfAbsent(rs.getString(3), k -> new ArrayList<>()).add(new Station(rs.getString(1), rs.getString(2), rs.getString(3)));
        });
        return jdbc.query("select code, name, workshop, outsourced from work_center order by code", (rs, n) ->
            new WorkCenter(rs.getString(1), rs.getString(2), rs.getString(3), rs.getBoolean(4), stations.getOrDefault(rs.getString(1), List.of())));
    }
    @Transactional(readOnly = true)
    public WorkCenter get(String code) {
        return list().stream().filter(w -> w.code().equals(code)).findFirst()
            .orElseThrow(() -> Errors.notFound("WORK_CENTER_NOT_FOUND", "工作中心不存在: " + code));
    }
    /** 工艺路线发布校验使用：判断工作中心是否存在。 */
    boolean exists(String code) {
        Integer n = jdbc.queryForObject("select count(*) from work_center where code = ?", Integer.class, code);
        return n != null && n > 0;
    }
    @Transactional
    public WorkCenter create(CreateWorkCenter r) {
        try {
            jdbc.update("insert into work_center (code, name, workshop, outsourced) values (?, ?, ?, ?)", r.code(), r.name(), r.workshop(), r.outsourced());
        } catch (DuplicateKeyException e) {
            throw Errors.conflict("WORK_CENTER_EXISTS", "工作中心已存在: " + r.code());
        }
        return get(r.code());
    }
    /** 新增工位；工作中心不存在返回 404，工位编码全局唯一。 */
    @Transactional
    public WorkCenter addStation(String workCenterCode, CreateStation r) {
        if (!exists(workCenterCode)) throw Errors.notFound("WORK_CENTER_NOT_FOUND", "工作中心不存在: " + workCenterCode);
        try {
            jdbc.update("insert into station (code, name, work_center_code) values (?, ?, ?)", r.code(), r.name(), workCenterCode);
        } catch (DuplicateKeyException e) {
            throw Errors.conflict("STATION_EXISTS", "工位已存在: " + r.code());
        } catch (DataIntegrityViolationException e) {
            // 检查与插入之间工作中心被删除的极端情况，由外键兜底。
            throw Errors.notFound("WORK_CENTER_NOT_FOUND", "工作中心不存在: " + workCenterCode);
        }
        return get(workCenterCode);
    }
}
