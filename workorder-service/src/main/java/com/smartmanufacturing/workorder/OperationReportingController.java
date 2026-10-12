package com.smartmanufacturing.workorder;
import com.smartmanufacturing.api.ApiResponse;
import com.smartmanufacturing.api.workorder.*;
import jakarta.validation.Valid;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import java.util.List;
import java.util.regex.Pattern;

/**
 * 工序报工 HTTP 入口（网关路径 /api/workorders/**）。三个写接口都必须携带 Idempotency-Key：
 * 首次成功返回 201（完工 200），同键重放返回首次的状态码与响应体并带 Idempotent-Replayed: true。
 */
@PreAuthorize("hasAuthority('workorder:read')")
@RestController
@RequestMapping("/workorders/{id}")
public class OperationReportingController {
    private static final Pattern KEY = Pattern.compile("[A-Za-z0-9._:-]{1,128}");
    private final OperationReportingService service;
    public OperationReportingController(OperationReportingService service) { this.service = service; }

    /** 工序报工。超上限 422 over-report；工单关闭 409 workorder-closed。 */
    @PreAuthorize("hasAuthority('workorder:write')")
    @PostMapping("/operations/{opNo}/reports")
    public ResponseEntity<String> report(@PathVariable String id, @PathVariable String opNo, @Valid @RequestBody ReportOperationRequest request,
                                         @RequestHeader(value = "Idempotency-Key", required = false) String key, Authentication auth) {
        return respond(service.report(id, opNo, request, key(key), auth.getName()));
    }

    /** 整条冲销报工。影响下游 409 downstream-reported；重复冲销 409 report-already-reversed。 */
    @PreAuthorize("hasAuthority('workorder:write')")
    @PostMapping("/reports/{reportId}/reversal")
    public ResponseEntity<String> reverse(@PathVariable String id, @PathVariable String reportId, @Valid @RequestBody ReverseReportRequest request,
                                          @RequestHeader(value = "Idempotency-Key", required = false) String key, Authentication auth) {
        return respond(service.reverse(id, reportId, request, key(key), auth.getName()));
    }

    /** 手工完工（允许短缺）。存在未报工工序 409 operations-unreported。 */
    @PreAuthorize("hasAuthority('workorder:write')")
    @PostMapping("/complete")
    public ResponseEntity<String> complete(@PathVariable String id, @Valid @RequestBody CompleteWorkOrderRequest request,
                                           @RequestHeader(value = "Idempotency-Key", required = false) String key, Authentication auth) {
        return respond(service.complete(id, request, key(key), auth.getName()));
    }

    @GetMapping("/progress")
    public ApiResponse<WorkOrderProgressView> progress(@PathVariable String id) { return ApiResponse.ok(service.progressView(id)); }

    @GetMapping("/reports")
    public ApiResponse<List<OperationReportView>> reports(@PathVariable String id) { return ApiResponse.ok(service.reports(id)); }

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
