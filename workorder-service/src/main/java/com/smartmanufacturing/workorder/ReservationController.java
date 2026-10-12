package com.smartmanufacturing.workorder;
import com.smartmanufacturing.api.*;
import jakarta.validation.Valid;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
/** 预留单接口与普通工单分离；查看者只能查询，写入要求工单操作权限。 */
@RestController
@RequestMapping("/workorders/reservations")
public class ReservationController {
    private final ReservationService service;
    private final JdbcTemplate jdbc;
    public ReservationController(ReservationService service,JdbcTemplate jdbc){this.service=service;this.jdbc=jdbc;}
    /** 查询本服务已提交的预留单，AT 一阶段数据不是跨库强隔离快照。 */
    @GetMapping @PreAuthorize("hasAuthority('workorder:read')")
    public ApiResponse<List<Reservation>> list(){
        return ApiResponse.ok(jdbc.query("select reservation_id,device_id from work_order_reservation order by reservation_id",
            (rs,row)->new Reservation(rs.getString(1),rs.getString(2))));
    }
    /** 创建跨库预留单，成功表示全局事务已提交。 */
    @PostMapping @ResponseStatus(HttpStatus.CREATED) @PreAuthorize("hasAuthority('workorder:write')")
    public ApiResponse<Reservation> create(@Valid @RequestBody CreateReservation request){return ApiResponse.ok(service.reserve(request));}
    /** 释放跨库预留单，失败时保留原业务状态等待回滚。 */
    @DeleteMapping("/{id}") @PreAuthorize("hasAuthority('workorder:write')")
    public ApiResponse<String> release(@PathVariable String id){return ApiResponse.ok(service.release(id));}
}
