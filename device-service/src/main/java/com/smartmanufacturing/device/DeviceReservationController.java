package com.smartmanufacturing.device;
import com.smartmanufacturing.api.*;
import com.smartmanufacturing.common.BusinessException;
import jakarta.validation.Valid;
import java.util.List;
import org.apache.seata.core.context.RootContext;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
/** 设备预留分支由 AT 代理保存回滚日志；只能在有效全局事务中修改资源。 */
@RestController
@RequestMapping("/devices/reservations")
public class DeviceReservationController {
    private final JdbcTemplate jdbc;
    private final boolean enabled;
    public DeviceReservationController(JdbcTemplate jdbc,@Value("${seata.enabled:false}") boolean enabled) {
        this.jdbc=jdbc; this.enabled=enabled;
    }
    /** 查询预留用于运维和业务确认，不接受未经授权的资源操作。 */
    @GetMapping @PreAuthorize("hasAuthority('device:read')")
    public ApiResponse<List<Reservation>> list() {
        return ApiResponse.ok(jdbc.query("select reservation_id, device_id from device_reservation order by reservation_id",
            (rs,row)->new Reservation(rs.getString(1),rs.getString(2))));
    }
    /** 本地事务负责预留插入，唯一设备键保证同一设备不能被并发重复占用。 */
    @PostMapping @Transactional @PreAuthorize("hasAuthority('workorder:write')")
    public ApiResponse<Reservation> reserve(@Valid @RequestBody CreateReservation request) {
        requireGlobalTransaction();
        var devices=jdbc.query("select status from device where id=? for update",(rs,row)->rs.getString(1),request.deviceId());
        if(devices.isEmpty()) throw new BusinessException("DEVICE_NOT_FOUND","设备不存在",HttpStatus.NOT_FOUND);
        if(!"ONLINE".equals(devices.getFirst())) throw new BusinessException("DEVICE_OFFLINE","设备离线",HttpStatus.CONFLICT);
        try { jdbc.update("insert into device_reservation (reservation_id,device_id) values (?,?)",request.reservationId(),request.deviceId()); }
        catch(DuplicateKeyException e){throw new BusinessException("DEVICE_ALREADY_RESERVED","设备已被预留",HttpStatus.CONFLICT);}
        return ApiResponse.ok(new Reservation(request.reservationId(),request.deviceId()));
    }
    /** 释放也是全局事务分支，避免一个库释放而另一个库仍保留预留单。 */
    @DeleteMapping("/{id}") @Transactional @PreAuthorize("hasAuthority('workorder:write')")
    public ApiResponse<String> release(@PathVariable String id) {
        requireGlobalTransaction();
        jdbc.update("delete from device_reservation where reservation_id=?",id);
        return ApiResponse.ok(id);
    }
    /** 关闭 Seata 时拒绝资源修改，不退化为不具备跨库原子性的普通写入。 */
    private void requireGlobalTransaction(){
        if(!enabled || RootContext.getXID()==null)
            throw new BusinessException("GLOBAL_TRANSACTION_REQUIRED","设备预留需要启用 Seata 全局事务",HttpStatus.SERVICE_UNAVAILABLE);
    }
}
