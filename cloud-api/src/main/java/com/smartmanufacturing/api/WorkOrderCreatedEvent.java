package com.smartmanufacturing.api;

import jakarta.validation.constraints.*;
import java.time.Instant;

/** 工单创建事件契约；只携带业务数据和关联标识，禁止包含访问令牌或密码。 */
public record WorkOrderCreatedEvent(
        @NotBlank @Size(max = 64) String eventId,
        @Min(1) @Max(1) int schemaVersion,
        @Pattern(regexp = "WorkOrderCreated") @NotNull String eventType,
        @NotBlank @Size(max = 64) String orderId,
        @NotBlank @Size(max = 64) String deviceId,
        @NotBlank @Size(max = 128) String product,
        @Min(1) @Max(1000000) int quantity,
        @NotNull Instant occurredAt,
        @Pattern(regexp = "[a-zA-Z0-9-]{1,64}") String requestId) {
    public static final String TYPE = "WorkOrderCreated";
    public static final int VERSION = 1;
}
