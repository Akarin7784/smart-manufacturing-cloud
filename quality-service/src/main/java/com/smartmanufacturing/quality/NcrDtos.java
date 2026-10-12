package com.smartmanufacturing.quality;

import java.time.Instant;
import java.util.List;

/** 质检接口与事件使用的数据结构，字段名与 docs/api/quality-openapi.yaml 一致；数量一律为十进制字符串。 */
public final class NcrDtos {
    private NcrDtos() {}

    /** NCR 状态（契约 NcrStatus）。 */
    public enum Status { OPEN, UNDER_REVIEW, DISPOSED, CLOSED, VOIDED }

    /** 处置类型（契约 DispositionType）：本期均只登记记录，不改动库存。 */
    public enum DispositionType { REWORK, SCRAP, CONCESSION }

    /** 作废原因：正常冲销；冲销先于原报工到达后对冲。 */
    public enum VoidReason { REVERSED, REVERSED_BEFORE_REPORTED }

    /** 暂存冲销状态（契约 PendingReversalStatus）。 */
    public enum PendingStatus { WAITING, MATCHED, EXPIRED }

    /** 冲突类型，本期仅此一种。 */
    public static final String DISPOSED_REVERSAL_CONFLICT = "DISPOSED_REVERSAL_CONFLICT";

    /** 不合格品处置单（契约 Ncr）。 */
    public record Ncr(String ncrNo, Status status, String sourceReportId, String workOrderNo, String operationNo,
                      String materialCode, String lotNo, String uom, String defectQty, String disposedQty,
                      VoidReason voidReason, List<Disposition> dispositions, Instant createdAt, long version) {}

    /** 处置行（契约 Disposition）。 */
    public record Disposition(String dispositionId, DispositionType type, String qty, String uom, String reason,
                              String operator, Instant disposedAt) {}

    /**
     * 登记处置请求（契约 CreateDispositionRequest）。type 与 qty 以字符串接收、在服务层校验，
     * 使格式错误同样经过幂等执行器与指标统计，并统一返回 validation-error。
     */
    public record CreateDisposition(String type, String qty, String uom, String reason) {}

    /** 分页结果（契约 NcrPage / PendingReversalPage / ConflictPage 共用结构）。 */
    public record Page<T>(List<T> items, int page, int size, long total) {}

    /** 暂存冲销（契约 PendingReversal）。 */
    public record PendingReversal(String reversedReportId, String reversalId, PendingStatus status, Instant receivedAt, String ncrNo) {}

    /** 冲销冲突（契约 Conflict）。 */
    public record Conflict(String eventId, String type, String ncrNo, String reversedReportId, String reversalId,
                           String disposedQty, Instant recordedAt) {}
}
