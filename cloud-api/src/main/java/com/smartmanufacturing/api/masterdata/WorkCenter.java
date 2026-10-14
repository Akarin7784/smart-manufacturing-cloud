package com.smartmanufacturing.api.masterdata;
import java.util.List;
/**
 * 工作中心：工艺路线中工序引用的能力单元，按车间归属。outsourced 表示委外工序（如热处理）。
 * deleted 为软删除标志（PR2）：默认查询不返回已删除的工作中心 / 工位，includeDeleted=true 时返回并带此标志。
 */
public record WorkCenter(String code, String name, String workshop, boolean outsourced, List<Station> stations, boolean deleted) {
    /** 兼容 PR2 之前的构造方式：未删除。 */
    public WorkCenter(String code, String name, String workshop, boolean outsourced, List<Station> stations) {
        this(code, name, workshop, outsourced, stations, false);
    }
}
