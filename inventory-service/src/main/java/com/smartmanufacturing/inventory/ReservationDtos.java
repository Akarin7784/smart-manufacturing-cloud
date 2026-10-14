package com.smartmanufacturing.inventory;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.List;

/**
 * 预留接口数据契约，与 docs/api/inventory-openapi.yaml 中 CreateReservationRequest、Reservation、Shortage 等 schema 一一对应。
 * 字段名、必填项变更必须先改契约（契约测试会校验每个响应）。
 */
public final class ReservationDtos {
    private ReservationDtos() {}

    /** 预留单状态：ACTIVE 之外均为终态。 */
    public enum Status { ACTIVE, RELEASED, EXPIRED, CONSUMED }

    /** 释放原因，缺省 CALLER_REQUEST。 */
    /** 释放原因；WORK_ORDER_CLOSED：工单关闭时释放剩余预留（#72），追加在末尾，与工单侧枚举同名同序。 */
    public enum ReleaseReason { CALLER_REQUEST, WORK_ORDER_CANCELLED, COMPENSATION, WORK_ORDER_CLOSED }

    /** 整单预留请求：一条 BOM 快照行一行，同一物料可以多行。 */
    public record CreateReservation(
        @NotBlank @Size(max = 64) String workOrderNo,
        @NotEmpty @Size(max = 500) List<@Valid @NotNull CreateLine> lines) {}

    /** 需求行。 */
    public record CreateLine(
        @NotBlank @Size(max = 64) String materialCode,
        @NotNull String qty,
        @NotBlank @Size(max = 16) String uom,
        @NotBlank @Size(max = 128) String sourceLineId) {}

    /** 释放请求（可选）。 */
    public record ReleaseRequest(ReleaseReason reason) {}

    /** 按行扣减请求，批次号必填。 */
    public record ConsumeRequest(@NotNull String qty, @NotBlank @Size(max = 64) String lotNo) {}

    /** 预留单视图。 */
    public record Reservation(String reservationId, String workOrderNo, Status status, Instant expiresAt, List<Line> lines) {}

    /** 预留行视图；status 为 OPEN 或 CLOSED（剩余可扣减量为 0）。 */
    public record Line(String lineId, String sourceLineId, String materialCode, String uom, String qty,
                       String reservedQty, String consumedQty, String status) {}

    /** 预留缺口：按物料编码 + 计量单位汇总。 */
    public record Shortage(String materialCode, String uom, String requested, String available, String shortage, List<String> sourceLineIds) {}
}
