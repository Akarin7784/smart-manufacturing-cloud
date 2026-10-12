package com.smartmanufacturing.api.masterdata;
import java.util.List;
/** 工作中心：工艺路线中工序引用的能力单元，按车间归属。outsourced 表示委外工序（如热处理）。 */
public record WorkCenter(String code, String name, String workshop, boolean outsourced, List<Station> stations) {}
