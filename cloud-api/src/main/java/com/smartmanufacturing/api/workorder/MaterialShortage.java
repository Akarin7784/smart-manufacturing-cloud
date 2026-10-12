package com.smartmanufacturing.api.workorder;
import java.math.BigDecimal;
/** 库存预留 409 返回的单行缺料明细；工单服务不加工，原样转给调用方。 */
public record MaterialShortage(String materialCode, BigDecimal requested, BigDecimal available, String uom) {}
