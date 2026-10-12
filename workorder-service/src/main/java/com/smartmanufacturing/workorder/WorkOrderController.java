package com.smartmanufacturing.workorder;
import com.smartmanufacturing.api.*;
import com.smartmanufacturing.common.BusinessException;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import java.util.List;
import java.util.regex.Pattern;
/** 工单 HTTP 入口，负责输入校验和响应映射，业务流程交由应用服务处理。 */
@org.springframework.security.access.prepost.PreAuthorize("hasAuthority('workorder:read')")
@RestController
@RequestMapping("/workorders")
public class WorkOrderController {
    /** 幂等键格式：1–128 位字母、数字及 - _ . :，可容纳 UUID 或客户端单号。 */
    private static final Pattern KEY = Pattern.compile("[A-Za-z0-9._:-]{1,128}");
    private final WorkOrderService service;
    public WorkOrderController(WorkOrderService service) { this.service = service; }
    /**
     * 幂等创建工单：必须携带 Idempotency-Key 请求头。
     * 首次创建返回 201；同键同内容重放返回 200 及首次结果，并带 Idempotent-Replayed: true；同键不同内容返回 422。
     */
    @org.springframework.security.access.prepost.PreAuthorize("hasAuthority('workorder:write')")
    @PostMapping
    public ResponseEntity<ApiResponse<WorkOrder>> create(@RequestHeader(value = "Idempotency-Key", required = false) String key,
                                                         @Valid @RequestBody CreateWorkOrder request, Authentication auth) {
        // 缺失或非法的键在调用设备服务前拒绝，避免无法去重的写请求进入系统。
        if (key == null || !KEY.matcher(key).matches())
            throw new BusinessException("IDEMPOTENCY_KEY_REQUIRED", "请求头 Idempotency-Key 缺失或格式非法", HttpStatus.BAD_REQUEST);
        // 调用方取自已验证的 JWT，不信任任何外部身份请求头。
        WorkOrderService.CreateResult result = service.create(auth.getName(), key, request);
        return ResponseEntity.status(result.replayed() ? HttpStatus.OK : HttpStatus.CREATED)
            .header("Idempotent-Replayed", String.valueOf(result.replayed()))
            .body(ApiResponse.ok(result.order()));
    }
    /** 查询已持久化工单，便于确认创建结果。 */
    @GetMapping public ApiResponse<List<WorkOrder>> list() { return ApiResponse.ok(service.list()); }
}
