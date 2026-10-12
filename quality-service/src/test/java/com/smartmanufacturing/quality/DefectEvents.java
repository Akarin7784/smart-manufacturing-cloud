package com.smartmanufacturing.quality;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.time.Instant;
import java.util.UUID;

/** 测试用不良品事件构造器：字段与工单服务 DefectReported / DefectReversed 载荷一致（docs/messaging.md）。 */
final class DefectEvents {
    static final ObjectMapper MAPPER = new ObjectMapper();
    private DefectEvents() {}

    /** DefectReported：一次报工的不良。 */
    static ObjectNode reported(String eventId, String reportId, String workOrderNo, String qty, String uom) {
        ObjectNode n = MAPPER.createObjectNode();
        n.put("eventId", eventId);
        n.put("workOrderNo", workOrderNo);
        n.put("operationNo", "10");
        n.put("materialCode", "FG-GR60");
        n.put("lotNo", "LOT-1");
        n.put("uom", uom);
        n.put("defectQty", qty);
        n.put("reportId", reportId);
        n.put("occurredAt", Instant.now().toString());
        return n;
    }

    /** DefectReversed：只带 reversedReportId 与 reversalId（#20 已去掉 reportId）。 */
    static ObjectNode reversed(String eventId, String reversedReportId, String reversalId) {
        ObjectNode n = MAPPER.createObjectNode();
        n.put("eventId", eventId);
        n.put("workOrderNo", "WO-X");
        n.put("operationNo", "10");
        n.put("materialCode", "FG-GR60");
        n.put("uom", "件");
        n.put("defectQty", "1");
        n.put("reversedReportId", reversedReportId);
        n.put("reversalId", reversalId);
        n.put("occurredAt", Instant.now().toString());
        return n;
    }

    static String id() { return UUID.randomUUID().toString(); }
}
