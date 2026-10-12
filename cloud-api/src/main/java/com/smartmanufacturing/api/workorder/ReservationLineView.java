package com.smartmanufacturing.api.workorder;
import java.math.BigDecimal;
/**
 * 预留行：按物料汇总的叶子件需求量。lineId 为库存返回的预留行号（按行、按批次消耗时使用），
 * 发起预留的请求行中为 null。
 */
public record ReservationLineView(String lineId, String materialCode, BigDecimal quantity, String uom) {}
