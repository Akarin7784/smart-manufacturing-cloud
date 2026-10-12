package com.smartmanufacturing.workorder;
import com.smartmanufacturing.api.ApiResponse;
import com.smartmanufacturing.api.workorder.HoldRequest;
import com.smartmanufacturing.api.workorder.ResumeRequest;
import com.smartmanufacturing.api.workorder.WorkOrderReleaseView;
import com.smartmanufacturing.common.BusinessException;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import java.util.regex.Pattern;

/** 工单生命周期 HTTP 入口：下达、冻结、恢复与下达快照查询。 */
@PreAuthorize("hasAuthority('workorder:read')")
@RestController
@RequestMapping("/workorders/{id}")
public class WorkOrderLifecycleController {
    /** 与创建接口相同的幂等键格式。 */
    private static final Pattern KEY = Pattern.compile("[A-Za-z0-9._:-]{1,128}");
    private final WorkOrderReleaseService service;
    public WorkOrderLifecycleController(WorkOrderReleaseService service) { this.service = service; }

    /**
     * 幂等下达：必须携带 Idempotency-Key。首次下达与同键重放都返回 200，重放带 Idempotent-Replayed: true；
     * 已被其他键下达或状态不允许返回 409；主数据无效 422；物料短缺 409 MATERIAL_SHORTAGE。
     * 调用方需同时具备 workorder:write 与 masterdata:read（令牌会透传给主数据服务）。
     */
    @PreAuthorize("hasAuthority('workorder:write')")
    @PostMapping("/release")
    public ResponseEntity<ApiResponse<WorkOrderReleaseView>> release(@PathVariable String id,
            @RequestHeader(value = "Idempotency-Key", required = false) String key, Authentication auth) {
        if (key == null || !KEY.matcher(key).matches())
            throw new BusinessException("IDEMPOTENCY_KEY_REQUIRED", "请求头 Idempotency-Key 缺失或格式非法", HttpStatus.BAD_REQUEST);
        var result = service.release(id, key, auth.getName());
        return ResponseEntity.ok().header("Idempotent-Replayed", String.valueOf(result.replayed())).body(ApiResponse.ok(result.view()));
    }

    /** 冻结工单（同一原因重复冻结幂等）。 */
    @PreAuthorize("hasAuthority('workorder:write')")
    @PostMapping("/hold")
    public ApiResponse<WorkOrderReleaseView> hold(@PathVariable String id, @Valid @RequestBody HoldRequest request, Authentication auth) {
        return ApiResponse.ok(service.hold(id, request.reason(), request.note(), auth.getName()));
    }

    /** 解除一个冻结原因；全部解除后恢复到 resumeTarget。 */
    @PreAuthorize("hasAuthority('workorder:write')")
    @PostMapping("/resume")
    public ApiResponse<WorkOrderReleaseView> resume(@PathVariable String id, @Valid @RequestBody ResumeRequest request, Authentication auth) {
        return ApiResponse.ok(service.resume(id, request.reason(), request.note(), auth.getName()));
    }

    /** 查询下达快照（版本、工序任务、预留）。 */
    @GetMapping("/release")
    public ApiResponse<WorkOrderReleaseView> view(@PathVariable String id) { return ApiResponse.ok(service.view(id)); }
}
