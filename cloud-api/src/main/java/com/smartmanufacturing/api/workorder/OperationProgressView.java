package com.smartmanufacturing.api.workorder;
/**
 * 工序累计进度视图。limit 为本工序当前可报上限（首道 = 计划数，后续 = 上道累计合格数）；
 * effectiveReports 为未被冲销的报工条数。数量均为 6 位小数的十进制字符串；uom 在首次报工前为 null。
 */
public record OperationProgressView(String operationNo, int seq, String goodQty, String defectQty, String scrapQty,
                                    String uom, String limit, int effectiveReports) {}
