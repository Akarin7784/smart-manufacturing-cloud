package com.smartmanufacturing.api.workorder;
import java.time.Instant;
/** 关闭结果：status 恒为 CLOSED，previousStatus 为关闭前状态（COMPLETED）。 */
public record WorkOrderCloseView(String workOrderNo, String status, String previousStatus, Instant closedAt) {}
