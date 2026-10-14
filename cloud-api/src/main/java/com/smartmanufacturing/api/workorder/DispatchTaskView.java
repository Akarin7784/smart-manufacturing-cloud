package com.smartmanufacturing.api.workorder;
import java.time.Instant;
/**
 * 带派工信息的工序任务视图（契约 schema OperationTaskView）：派工类接口的响应与 GET /operation-tasks 列表项。
 * stationCode 为空表示未派工；version 为任务乐观锁版本，改派时作为 expectedVersion 回传。
 */
public record DispatchTaskView(String workOrderNo, String operationNo, String name, String workCenterCode, String status,
                               String stationCode, String operator, Instant plannedStart, Instant plannedEnd,
                               Instant dispatchedAt, String dispatchedBy, long version) {}
