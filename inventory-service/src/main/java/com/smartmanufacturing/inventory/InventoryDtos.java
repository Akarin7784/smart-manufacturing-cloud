package com.smartmanufacturing.inventory;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.time.Instant;

/**
 * 库位、批次、现存量与出入库流水接口的数据契约（与 docs/api/inventory-openapi.yaml 中同名 schema 一一对应）。
 * 数量字段一律为十进制字符串，格式见 {@link Quantities#PATTERN}。
 */
public final class InventoryDtos {
    private InventoryDtos() {}

    /** 新建库位请求。编码只允许字母、数字及 - _ .，便于作为条码打印。 */
    public record CreateLocation(
        @NotBlank @Size(max = 64) @Pattern(regexp = "[A-Za-z0-9._-]+") String code,
        @NotBlank @Size(max = 64) String warehouseCode,
        @NotBlank @Size(max = 128) String name) {}

    /** 库位视图。 */
    public record Location(String code, String warehouseCode, String name, boolean enabled) {}

    /**
     * 入库/出库请求：一次针对一个（物料、库位、批次）。refNo 为外部单据号（采购收货单、领料单等），仅用于追溯。
     */
    public record Movement(
        @NotBlank @Size(max = 64) String materialCode,
        @NotBlank @Size(max = 16) String uom,
        @NotBlank @Size(max = 64) String locationCode,
        @NotBlank @Size(max = 64) String lotNo,
        @NotNull String qty,
        @Size(max = 128) String refNo) {}

    /** 批次视图。 */
    public record Lot(String materialCode, String lotNo, Instant createdAt) {}

    /** 现存量视图：version 每次变更加一，可用于客户端判断数据是否已变化；inQcHold 标明该行位于质检隔离库位 QC-HOLD。 */
    public record Stock(String materialCode, String uom, String locationCode, String lotNo, String qty, boolean inQcHold, long version) {}

    /**
     * 可用量视图：available = onHand - reserved，reserved 为 ACTIVE 预留单未扣减部分之和。
     * onHand 不含隔离量；quarantined 为 QC-HOLD 库位余额合计，单独返回，不可用、不可预留。
     */
    public record Availability(String materialCode, String uom, String onHand, String reserved, String available, String quarantined) {}

    /** 出入库流水视图：qty 带符号（入库为正、出库与扣减为负），balanceAfter 为该库位批次变更后的余额。 */
    public record StockTxn(long txnId, String txnType, String materialCode, String uom, String locationCode, String lotNo,
                           String qty, String balanceAfter, String refType, String refNo, String operator, Instant occurredAt) {}

    /** 出库不足时的缺口明细（不含 sourceLineIds，与预留的 Shortage 区分）。 */
    public record StockShortage(String materialCode, String uom, String requested, String available, String shortage) {}
}
