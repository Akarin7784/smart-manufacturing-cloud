package com.smartmanufacturing.workorder.messaging;

import com.smartmanufacturing.api.ApiResponse;
import com.smartmanufacturing.common.BusinessException;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

/** 管理员观察和重试发件箱，不向普通查询用户暴露消息管理能力。 */
@RestController
@RequestMapping("/workorders/outbox")
@PreAuthorize("hasAuthority('metrics:read')")
public class OutboxController {
    private final OutboxStore store;
    public OutboxController(OutboxStore store) { this.store = store; }
    /** 查看发送状态计数，便于告警积压和失败。 */
    @GetMapping public ApiResponse<Map<String, Long>> counts() { return ApiResponse.ok(store.counts()); }
    /** 对 FAILED 事件执行人工重试，不允许重置仍在发送中的事件。 */
    @PostMapping("/{eventId}/retry") public ApiResponse<String> retry(@PathVariable String eventId) {
        if (!store.retry(eventId)) throw new BusinessException("OUTBOX_NOT_RETRYABLE", "事件不存在或不处于失败状态", HttpStatus.CONFLICT);
        return ApiResponse.ok(eventId);
    }
}
