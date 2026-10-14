package com.smartmanufacturing.masterdata;

import com.smartmanufacturing.api.ApiResponse;
import com.smartmanufacturing.api.workorder.WorkOrderReferenceView;
import feign.FeignException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 主数据引用检查：停用 / 删除前确认没有被引用，被引用返回 409 MASTERDATA_IN_USE（problem type = masterdata-in-use）。
 * <ul>
 *   <li>ROUTING：工作中心被未作废（DRAFT / RELEASED）的工艺路线工序引用；</li>
 *   <li>BOM：物料作为成品或组件出现在未作废的 BOM 中；</li>
 *   <li>WORK_ORDER：工作中心 / 工位被执行中的工单任务引用；BOM / 工艺路线草稿曾被任何工单引用（调用工单服务；不可用时 503，不放行）。</li>
 * </ul>
 */
@Component
class MasterdataReferences {
    private static final Logger LOG = LoggerFactory.getLogger(MasterdataReferences.class);
    private static final int SHOW = 10;
    private final JdbcTemplate jdbc;
    private final WorkOrderReferenceClient workOrders;

    MasterdataReferences(JdbcTemplate jdbc, WorkOrderReferenceClient workOrders) { this.jdbc = jdbc; this.workOrders = workOrders; }

    /** 本地检查：工作中心被未作废的工艺路线引用 409（ROUTING）。只查本库，可在持锁事务内调用。 */
    void requireNoRoutingUsing(String code) {
        List<String> routings = jdbc.queryForList("select distinct concat(o.product_code, '/', o.version) from routing_operation o join routing_header h"
            + " on h.product_code = o.product_code and h.version = o.version where o.work_center_code = ? and h.status <> 'OBSOLETE'"
            + " order by 1", String.class, code);
        if (!routings.isEmpty())
            throw Errors.inUse("ROUTING", "工作中心 " + code + " 仍被 " + routings.size() + " 个未作废的工艺路线引用", head(routings));
    }

    /** 远程检查：工作中心被执行中的工单引用 409（WORK_ORDER），工单服务不可用 503。调用 Feign，不要在持有行锁的事务内调用。 */
    void requireNoWorkOrderUsingWorkCenter(String code) { requireNoWorkOrder(code, null, "工作中心 " + code); }

    /** 远程检查：工位被执行中的工单引用 409（WORK_ORDER）。调用 Feign，不要在持有行锁的事务内调用。 */
    void requireStationUnused(String code) { requireNoWorkOrder(null, code, "工位 " + code); }

    void requireMaterialUnused(String code) {
        List<String> boms = jdbc.queryForList("select distinct concat(h.product_code, '/', h.version) from bom_header h"
            + " where h.status <> 'OBSOLETE' and (h.product_code = ? or exists (select 1 from bom_line l where l.product_code = h.product_code"
            + " and l.version = h.version and l.component_code = ?)) order by 1", String.class, code, code);
        if (!boms.isEmpty())
            throw Errors.inUse("BOM", "物料 " + code + " 仍被 " + boms.size() + " 个未作废的 BOM 引用", head(boms));
    }

    /**
     * 删除 BOM / 工艺路线草稿前确认从未被任何工单引用（PM 决策：只有从未被引用的草稿可以硬删除），
     * 被引用 409 MASTERDATA_IN_USE（referenceType = WORK_ORDER），工单服务不可用 503，不放行。
     */
    void requireVersionNeverReferenced(String type, String product, String version) {
        String what = ("BOM".equals(type) ? "BOM " : "工艺路线 ") + product + "/" + version;
        requireNoWorkOrder(() -> workOrders.versionReferences(type, product, version), what, "删除", "曾被 ");
    }

    private void requireNoWorkOrder(String workCenter, String station, String what) {
        requireNoWorkOrder(() -> workOrders.references(workCenter, station), what, "停用", "仍被 ");
    }

    private void requireNoWorkOrder(java.util.function.Supplier<ApiResponse<WorkOrderReferenceView>> call, String what, String action, String verb) {
        WorkOrderReferenceView view;
        try {
            ApiResponse<WorkOrderReferenceView> r = call.get();
            view = r == null ? null : r.data();
        } catch (FeignException.Forbidden e) {
            throw new com.smartmanufacturing.common.BusinessException("ACCESS_DENIED", "工单服务拒绝引用检查（权限不足）", org.springframework.http.HttpStatus.FORBIDDEN);
        } catch (RuntimeException e) {
            LOG.warn("工单服务引用检查失败，拒绝{} {}", action, what, e);
            throw Errors.workOrderUnavailable("工单服务暂时不可用，无法确认" + what + "是否被工单引用，未" + action);
        }
        if (view == null) throw Errors.workOrderUnavailable("工单服务返回空结果，无法确认" + what + "是否被工单引用，未" + action);
        if (view.count() > 0)
            throw Errors.inUse("WORK_ORDER", what + " " + verb + view.count() + " 个" + ("仍被 ".equals(verb) ? "执行中的" : "") + "工单引用", view.workOrderNos());
    }

    private static List<String> head(List<String> all) { return all.subList(0, Math.min(SHOW, all.size())); }
}
