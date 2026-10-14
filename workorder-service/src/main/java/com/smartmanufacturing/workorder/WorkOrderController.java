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
    /** 分页查询工单：page 从 0 开始，size 1–100（默认 20）；status 精确过滤，keyword 匹配工单号或产品。 */
    @GetMapping public ApiResponse<PageView<WorkOrder>> list(@RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "20") int size,
                                                             @RequestParam(required = false) String status, @RequestParam(required = false) String keyword) {
        return ApiResponse.ok(service.list(page, size, status, keyword));
    }

    /**
     * 主数据引用检查：主数据服务停用工作中心 / 工位前调用（令牌透传）。调用者具备 workorder:read 或 masterdata:write 即可，
     * 只返回引用数量与前 10 个工单号，不暴露工单明细。
     */
    @org.springframework.security.access.prepost.PreAuthorize("hasAnyAuthority('workorder:read', 'masterdata:write')")
    @GetMapping("/references")
    public ApiResponse<com.smartmanufacturing.api.workorder.WorkOrderReferenceView> references(@RequestParam(required = false) String workCenter,
                                                                                            @RequestParam(required = false) String station) {
        return ApiResponse.ok(service.references(workCenter, station));
    }
}
