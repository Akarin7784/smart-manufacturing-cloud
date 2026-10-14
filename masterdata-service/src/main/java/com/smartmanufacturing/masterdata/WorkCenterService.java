package com.smartmanufacturing.masterdata;

import com.smartmanufacturing.api.masterdata.*;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import java.util.*;

/**
 * 工厂模型服务：工作中心与工位。数据量小且结构简单，数据访问直接放在本服务内，
 * 但仍统一使用参数绑定。设备实例不在此维护，由 device-service 按工位编码绑定。
 * <p>PR2：修改与软删除（deleted 标志）。已删除的工作中心 / 工位默认不返回（查询单个返回 404），不能再被新工艺路线发布或派工引用；
 * 删除前做引用检查（{@link MasterdataReferences}），被引用返回 409 MASTERDATA_IN_USE。编码不可复用（已删除的编码再次创建仍 409）。
 */
@Service
public class WorkCenterService {
    private final JdbcTemplate jdbc;
    private final MasterdataReferences references;
    private final TransactionTemplate tx;
    WorkCenterService(JdbcTemplate jdbc, MasterdataReferences references, PlatformTransactionManager txManager) {
        this.jdbc = jdbc; this.references = references; this.tx = new TransactionTemplate(txManager);
    }

    /** 列出工作中心及工位（演示工厂规模下无需分页）；includeDeleted=false 时隐藏已删除的工作中心与工位。 */
    @Transactional(readOnly = true)
    public List<WorkCenter> list(boolean includeDeleted) {
        Map<String, List<Station>> stations = new HashMap<>();
        jdbc.query("select code, name, work_center_code, deleted from station" + (includeDeleted ? "" : " where deleted = false") + " order by code", rs -> {
            stations.computeIfAbsent(rs.getString(3), k -> new ArrayList<>()).add(new Station(rs.getString(1), rs.getString(2), rs.getString(3), rs.getBoolean(4)));
        });
        return jdbc.query("select code, name, workshop, outsourced, deleted from work_center" + (includeDeleted ? "" : " where deleted = false") + " order by code", (rs, n) ->
            new WorkCenter(rs.getString(1), rs.getString(2), rs.getString(3), rs.getBoolean(4), stations.getOrDefault(rs.getString(1), List.of()), rs.getBoolean(5)));
    }
    public List<WorkCenter> list() { return list(false); }

    /** 未删除的工作中心及其未删除的工位；不存在或已删除 404。 */
    @Transactional(readOnly = true)
    public WorkCenter get(String code) {
        return list(false).stream().filter(w -> w.code().equals(code)).findFirst()
            .orElseThrow(() -> Errors.notFound("WORK_CENTER_NOT_FOUND", "工作中心不存在: " + code));
    }
    /** 工艺路线发布校验使用：判断工作中心是否存在且未删除。 */
    boolean exists(String code) {
        Integer n = jdbc.queryForObject("select count(*) from work_center where code = ? and deleted = false", Integer.class, code);
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
    /** 新增工位；工作中心不存在或已删除返回 404，工位编码全局唯一（含已删除的）。 */
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

    /** 修改工作中心名称、车间、委外标志；不存在或已删除 404。 */
    @Transactional
    public WorkCenter update(String code, UpdateWorkCenter r) {
        if (jdbc.update("update work_center set name = ?, workshop = ?, outsourced = ? where code = ? and deleted = false",
                r.name(), r.workshop(), r.outsourced(), code) == 0)
            throw Errors.notFound("WORK_CENTER_NOT_FOUND", "工作中心不存在: " + code);
        return get(code);
    }

    /**
     * 软删除工作中心（连同其工位）：被未作废的工艺路线引用 409（ROUTING），被执行中的工单引用 409（WORK_ORDER），
     * 工单服务不可用 503。工位上的工单任务必然属于该工作中心，所以按工作中心检查即可覆盖其工位。
     * <p>#74：远程（工单服务）检查在事务外、加锁前执行，避免持有 FOR UPDATE 行锁等待 Feign；
     * 之后在短事务内加锁、复核存在性与本地 ROUTING 引用再删除。远程检查到删除之间存在空窗（尽力而为，见 masterdata-openapi.yaml）。
     */
    public void delete(String code) {
        get(code);
        references.requireNoRoutingUsing(code);
        references.requireNoWorkOrderUsingWorkCenter(code);
        tx.executeWithoutResult(s -> {
            lockWorkCenter(code);
            references.requireNoRoutingUsing(code);
            jdbc.update("update station set deleted = true where work_center_code = ?", code);
            jdbc.update("update work_center set deleted = true where code = ?", code);
        });
    }

    /** 修改工位名称；工位不存在、已删除或不属于该工作中心 404。 */
    @Transactional
    public WorkCenter updateStation(String workCenterCode, String code, UpdateStation r) {
        if (jdbc.update("update station set name = ? where code = ? and work_center_code = ? and deleted = false", r.name(), code, workCenterCode) == 0)
            throw Errors.notFound("STATION_NOT_FOUND", "工位不存在: " + workCenterCode + "/" + code);
        return get(workCenterCode);
    }

    /**
     * 软删除工位：被执行中的工单引用 409（WORK_ORDER），工单服务不可用 503。
     * #74：先（无锁）确认存在并做远程检查，再在短事务内加锁复核存在后删除，不在持锁时调用 Feign。
     */
    public void deleteStation(String workCenterCode, String code) {
        requireStation(workCenterCode, code, "");
        references.requireStationUnused(code);
        tx.executeWithoutResult(s -> {
            requireStation(workCenterCode, code, " for update");
            jdbc.update("update station set deleted = true where code = ?", code);
        });
    }

    private void requireStation(String workCenterCode, String code, String lock) {
        if (jdbc.queryForList("select code from station where code = ? and work_center_code = ? and deleted = false" + lock, String.class, code, workCenterCode).isEmpty())
            throw Errors.notFound("STATION_NOT_FOUND", "工位不存在: " + workCenterCode + "/" + code);
    }

    private void lockWorkCenter(String code) {
        if (jdbc.queryForList("select code from work_center where code = ? and deleted = false for update", String.class, code).isEmpty())
            throw Errors.notFound("WORK_CENTER_NOT_FOUND", "工作中心不存在: " + code);
    }
}
