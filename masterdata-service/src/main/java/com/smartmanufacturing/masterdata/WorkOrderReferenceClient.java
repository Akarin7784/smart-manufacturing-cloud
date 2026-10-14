package com.smartmanufacturing.masterdata;

import com.smartmanufacturing.api.ApiResponse;
import com.smartmanufacturing.api.workorder.WorkOrderReferenceView;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;

/**
 * 工单服务引用检查（停用工作中心 / 工位前调用）。URL 为空时走服务发现；由 {@link MasterdataFeignConfiguration} 透传已验证的 Bearer 令牌，
 * 工单服务接受 masterdata:write。不配置降级：失败由 {@link MasterdataReferences} 翻译为 503，不放行。
 */
@FeignClient(name = "workorder-service", url = "${clients.workorder.url:}")
public interface WorkOrderReferenceClient {
    @GetMapping("/workorders/references")
    ApiResponse<WorkOrderReferenceView> references(@RequestParam(value = "workCenter", required = false) String workCenter,
                                                   @RequestParam(value = "station", required = false) String station);

    /** BOM / 工艺路线版本是否被任何工单（含已完工、已关闭、已撤回下达）引用过；删除草稿前调用。type 为 BOM 或 ROUTING。 */
    @GetMapping("/workorders/version-references")
    ApiResponse<WorkOrderReferenceView> versionReferences(@RequestParam("type") String type, @RequestParam("product") String product,
                                                          @RequestParam("version") String version);
}
