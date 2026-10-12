package com.smartmanufacturing.api.masterdata;
import java.time.LocalDate;
import java.util.List;
/** 单层 BOM 版本：生效区间为 [effectiveFrom, effectiveTo)，effectiveTo 为空表示长期有效。 */
public record Bom(String productCode, String version, LifecycleStatus status,
                  LocalDate effectiveFrom, LocalDate effectiveTo, List<BomLine> lines) {}
