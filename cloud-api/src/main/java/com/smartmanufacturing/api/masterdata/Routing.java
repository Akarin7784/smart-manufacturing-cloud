package com.smartmanufacturing.api.masterdata;
import java.time.LocalDate;
import java.util.List;
/** 工艺路线版本，工序按 opNo 数字升序排列；生效规则与 BOM 相同。 */
public record Routing(String productCode, String version, LifecycleStatus status,
                      LocalDate effectiveFrom, LocalDate effectiveTo, List<Operation> operations) {}
