package com.smartmanufacturing.api.workorder;
import java.time.Instant;
/**
 * 报工流水视图；数量为 6 位小数的十进制字符串，冲销记录为负数，reversedReportId 指向被冲销的原报工。
 * stationCode 为报工当时任务所在工位（之后改派不变，未派工报工为 null）；laborHours 为本条工时（2 位小数，冲销为负）。
 */
public record OperationReportView(String reportId, String type, String operationNo, String goodQty, String defectQty,
                                  String scrapQty, String uom, String lotNo, String note, String reversedReportId,
                                  String operator, Instant createdAt, String stationCode, String laborHours) {}
