package com.smartmanufacturing.api.masterdata;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
/** 指定日期、指定数量下的多层 BOM 展开结果，即工单下达时的 BOM 快照来源。 */
public record BomExplosion(String productCode, String bomVersion, BigDecimal quantity, LocalDate date, List<BomExplosionLine> lines) {}
