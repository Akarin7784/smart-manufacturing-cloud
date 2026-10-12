package com.smartmanufacturing.api.workorder;
import java.time.Instant;
import java.util.List;
/**
 * 工单下达快照视图：冻结的 BOM / 工艺版本、工序任务、BOM 展开行数与物料预留。
 * version 为生命周期乐观锁版本；resumeTarget 仅在 ON_HOLD 时有值。
 */
public record WorkOrderReleaseView(String orderId, String status, long version, String productCode,
                                   String bomVersion, String routingVersion, Instant releasedAt,
                                   String resumeTarget, List<String> holdReasons,
                                   int bomLineCount, List<OperationTaskView> operations,
                                   MaterialReservationView reservation) {}
