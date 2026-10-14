package com.smartmanufacturing.api.workorder;
import jakarta.validation.constraints.*;
import java.time.Instant;
/**
 * 派工 / 改派请求。stationCode 必须是该工序工作中心下的工位；operator 为指派操作工的 auth 用户 id（取自 GET /auth/operators，可空，
 * M1 只记录不限制）；计划起止可空，同时给出时结束须晚于开始。expectedVersion 仅改派必填（任务乐观锁版本），派工时忽略。
 */
public record DispatchOperationRequest(
    @NotBlank @Size(max = 64) String stationCode,
    @Size(max = 64) String operator,
    Instant plannedStart,
    Instant plannedEnd,
    @Size(max = 256) String note,
    Long expectedVersion) {}
