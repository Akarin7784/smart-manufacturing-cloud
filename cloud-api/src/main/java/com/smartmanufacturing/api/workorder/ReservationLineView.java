package com.smartmanufacturing.api.workorder;
import java.math.BigDecimal;
/** 预留行：按物料汇总的叶子件需求量。 */
public record ReservationLineView(String materialCode, BigDecimal quantity, String uom) {}
