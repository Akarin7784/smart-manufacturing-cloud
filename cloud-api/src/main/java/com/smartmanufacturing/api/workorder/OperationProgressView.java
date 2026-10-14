package com.smartmanufacturing.api.workorder;
import java.time.Instant;
/**
 * 工序累计进度视图。limit 为本工序当前可报上限（首道 = 计划数，后续 = 上道累计合格数）；
 * effectiveReports 为未被冲销的报工条数。数量均为 6 位小数的十进制字符串；uom 在首次报工前为 null。
 * M1 起附带派工信息：taskStatus、stationCode / operator（未派工为 null）、计划起止、任务版本 version（改派用的 expectedVersion），
 * 以及工序累计工时 laborHours（2 位小数字符串）。PR2 起附带工序名称 operationName 与工作中心 workCenterCode（取自下达时的工艺快照）。
 */
public record OperationProgressView(String operationNo, int seq, String goodQty, String defectQty, String scrapQty,
                                    String uom, String limit, int effectiveReports, String laborHours, String taskStatus,
                                    String stationCode, String operator, Instant plannedStart, Instant plannedEnd, long version,
                                    String operationName, String workCenterCode) {}
