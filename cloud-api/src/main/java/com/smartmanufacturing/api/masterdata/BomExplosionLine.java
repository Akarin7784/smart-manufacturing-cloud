package com.smartmanufacturing.api.masterdata;
import java.math.BigDecimal;
/**
 * 多层展开结果行。level 从 1 开始；path 为“父/子/…”编码路径，用于界面树形展示与追溯；
 * requiredQty 为按需求数量与逐层损耗计算的累计需求；sourceBomVersion 记录本行来自哪个 BOM 版本，供工单快照冻结。
 */
public record BomExplosionLine(int level, String path, String parentCode, String componentCode,
                               BigDecimal qtyPer, BigDecimal requiredQty, String uom, IssueMode issueMode,
                               String consumeOpNo, boolean leaf, String sourceBomVersion) {}
