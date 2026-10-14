package com.smartmanufacturing.workorder;

import com.smartmanufacturing.api.ApiResponse;
import com.smartmanufacturing.api.workorder.DispatchOperationRequest;
import com.smartmanufacturing.api.workorder.DispatchTaskView;
import com.smartmanufacturing.api.workorder.TaskActionRequest;
import jakarta.validation.Valid;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import java.util.List;
import java.util.regex.Pattern;

/**
 * 工序派工与任务状态 HTTP 入口（网关 /api/workorders/{no}/operations/{op}/… 与 /api/operation-tasks）。
 * <p>权限：派工类需要 execution:dispatch，开工 / 暂停 / 恢复需要 execution:report；兼容期内持有 workorder:write 也可调用（P2 收紧）。
 * 写接口必须带 Idempotency-Key，成功 200，同键重放带 Idempotent-Replayed: true。
 */
@RestController
public class OperationTaskController {
    private static final Pattern KEY = Pattern.compile("[A-Za-z0-9._:-]{1,128}");
    private static final String BASE = "/workorders/{id}/operations/{opNo}";
    private final OperationTaskService service;
    public OperationTaskController(OperationTaskService service) { this.service = service; }

    @PreAuthorize("hasAnyAuthority('execution:dispatch', 'workorder:write')")
    @PostMapping(BASE + "/dispatch")
    public ResponseEntity<String> dispatch(@PathVariable String id, @PathVariable String opNo, @Valid @RequestBody DispatchOperationRequest request,
                                           @RequestHeader(value = "Idempotency-Key", required = false) String key, Authentication auth) {
        return respond(service.dispatch(id, opNo, request, key(key), auth.getName()));
    }

    @PreAuthorize("hasAnyAuthority('execution:dispatch', 'workorder:write')")
    @PostMapping(BASE + "/reassign")
    public ResponseEntity<String> reassign(@PathVariable String id, @PathVariable String opNo, @Valid @RequestBody DispatchOperationRequest request,
                                           @RequestHeader(value = "Idempotency-Key", required = false) String key, Authentication auth) {
        return respond(service.reassign(id, opNo, request, key(key), auth.getName()));
    }

    @PreAuthorize("hasAnyAuthority('execution:dispatch', 'workorder:write')")
    @PostMapping(BASE + "/cancel-dispatch")
    public ResponseEntity<String> cancelDispatch(@PathVariable String id, @PathVariable String opNo, @Valid @RequestBody(required = false) TaskActionRequest request,
                                                 @RequestHeader(value = "Idempotency-Key", required = false) String key, Authentication auth) {
        return respond(service.cancelDispatch(id, opNo, request, key(key), auth.getName()));
    }

    @PreAuthorize("hasAnyAuthority('execution:report', 'workorder:write')")
    @PostMapping(BASE + "/start")
    public ResponseEntity<String> start(@PathVariable String id, @PathVariable String opNo, @Valid @RequestBody(required = false) TaskActionRequest request,
                                        @RequestHeader(value = "Idempotency-Key", required = false) String key, Authentication auth) {
        return respond(service.start(id, opNo, request, key(key), auth.getName()));
    }

    @PreAuthorize("hasAnyAuthority('execution:report', 'workorder:write')")
    @PostMapping(BASE + "/pause")
    public ResponseEntity<String> pause(@PathVariable String id, @PathVariable String opNo, @Valid @RequestBody(required = false) TaskActionRequest request,
                                        @RequestHeader(value = "Idempotency-Key", required = false) String key, Authentication auth) {
        return respond(service.pause(id, opNo, request, key(key), auth.getName()));
    }

    @PreAuthorize("hasAnyAuthority('execution:report', 'workorder:write')")
    @PostMapping(BASE + "/resume")
    public ResponseEntity<String> resume(@PathVariable String id, @PathVariable String opNo, @Valid @RequestBody(required = false) TaskActionRequest request,
                                         @RequestHeader(value = "Idempotency-Key", required = false) String key, Authentication auth) {
        return respond(service.resume(id, opNo, request, key(key), auth.getName()));
    }

    /** 工位工作台任务列表，需要 workorder:read。 */
    @PreAuthorize("hasAuthority('workorder:read')")
    @GetMapping("/operation-tasks")
    public ApiResponse<List<DispatchTaskView>> list(@RequestParam(required = false) String station,
                                                    @RequestParam(required = false) List<String> status,
                                                    @RequestParam(required = false) String operator,
                                                    @RequestParam(defaultValue = "0") int page,
                                                    @RequestParam(defaultValue = "50") int size) {
        return ApiResponse.ok(service.list(station, status, operator, page, size));
    }

    private static String key(String key) {
        if (key == null || !KEY.matcher(key).matches())
            throw WorkOrderProblemType.IDEMPOTENCY_KEY_REQUIRED.exception("请求头 Idempotency-Key 缺失或格式非法");
        return key;
    }

    private static ResponseEntity<String> respond(OperationReportingService.CommandResult r) {
        return ResponseEntity.status(r.status()).contentType(MediaType.APPLICATION_JSON)
            .header("Idempotent-Replayed", String.valueOf(r.replayed())).body(r.body());
    }
}
