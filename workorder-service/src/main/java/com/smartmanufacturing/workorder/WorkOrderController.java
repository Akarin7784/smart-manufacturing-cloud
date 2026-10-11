package com.smartmanufacturing.workorder;
import com.smartmanufacturing.api.*;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import java.util.List;
/** 工单 HTTP 入口，负责输入校验和响应映射，业务流程交由应用服务处理。 */
@org.springframework.security.access.prepost.PreAuthorize("hasAuthority('workorder:read')")
@RestController
@RequestMapping("/workorders")
public class WorkOrderController {
    private final WorkOrderService service;
    public WorkOrderController(WorkOrderService service) { this.service = service; }
    /** 先执行输入校验，再创建工单，成功返回 HTTP 201。 */
    @org.springframework.security.access.prepost.PreAuthorize("hasAuthority('workorder:write')")
    @PostMapping @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<WorkOrder> create(@Valid @RequestBody CreateWorkOrder request) {
        return ApiResponse.ok(service.create(request));
    }
    /** 查询已持久化工单，便于确认创建结果。 */
    @GetMapping public ApiResponse<List<WorkOrder>> list() { return ApiResponse.ok(service.list()); }
}
