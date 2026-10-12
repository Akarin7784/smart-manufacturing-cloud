package com.smartmanufacturing.quality;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.http.MediaType;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import java.nio.charset.StandardCharsets;

/**
 * 库存 QC-HOLD 内部接口的 HTTP 实现（契约 docs/api/inventory-openapi.yaml 的 /inventory/qc-hold/**，操作级 servers 为内部直连）。
 * <ul>
 *   <li>HOLD → receipts；SCRAP → scraps；RELEASE（REWORK/CONCESSION，带 dispositionId）与 VOID（NCR_VOIDED，不带 dispositionId）→ releases。</li>
 *   <li>请求体只放契约字段，不带库位（库存固定为 QC-HOLD，多余字段 400）；lotNo 为空时不发送，由库存按必填校验拒绝（400）。</li>
 *   <li>Idempotency-Key 为任务上固定的键，重试与人工重试都不变；库存按 client_id 作用域去重，保留 7 天。</li>
 *   <li>401：作废缓存令牌重取一次再调；仍 401 按可重试处理（令牌轮换期间偶发）。</li>
 *   <li>结果分类见 {@link #classify}：只有能证明库存未记账的拒绝才标 certain=true。</li>
 * </ul>
 */
class HttpInventoryQcHoldClient implements InventoryQcHoldClient {
    private final RestClient inventory;
    private final QcHoldTokenProvider tokens;
    private final ObjectMapper mapper;
    private final WritebackProperties.Client props;

    HttpInventoryQcHoldClient(RestClient inventory, QcHoldTokenProvider tokens, ObjectMapper mapper, WritebackProperties.Client props) {
        this.inventory = inventory; this.tokens = tokens; this.mapper = mapper; this.props = props;
    }

    /** 一次 HTTP 交换的原始结果。 */
    record Raw(int status, String body, String replayed) {}

    @Override public boolean ready() { return props.configured(); }

    @Override
    public Outcome call(Call call) {
        try {
            Raw raw = post(call, tokens.token());
            if (raw.status() == 401) {
                // 令牌可能已被轮换或提前失效：作废缓存重取一次（设计 §2）。
                tokens.invalidate();
                raw = post(call, tokens.token());
            }
            return classify(raw);
        } catch (QcHoldTokenProvider.TokenException e) {
            return new Retryable(truncate(e.getMessage()));
        } catch (RestClientException e) {
            // 连接失败、读超时等：请求可能已到达库存并记账，结果不确定，按可重试处理（同一幂等键重放安全）。
            return new Retryable(truncate("调用库存失败：" + e.getMessage()));
        }
    }

    private Raw post(Call call, String token) {
        return inventory.post().uri(path(call.action()))
            .headers(h -> { h.setBearerAuth(token); h.set("Idempotency-Key", call.idempotencyKey()); })
            .contentType(MediaType.APPLICATION_JSON).accept(MediaType.APPLICATION_JSON, MediaType.APPLICATION_PROBLEM_JSON)
            .body(body(call))
            .exchange((req, res) -> new Raw(res.getStatusCode().value(),
                new String(res.getBody().readAllBytes(), StandardCharsets.UTF_8), res.getHeaders().getFirst("Idempotent-Replayed")));
    }

    /** 动作 → 库存接口路径。 */
    static String path(Action action) {
        return switch (action) {
            case HOLD -> "/inventory/qc-hold/receipts";
            case SCRAP -> "/inventory/qc-hold/scraps";
            case RELEASE, VOID -> "/inventory/qc-hold/releases";
        };
    }

    /** 按契约组装请求体：receipts 只有基础字段；releases 带 reason（VOID 不带 dispositionId）；scraps 带 dispositionId。 */
    ObjectNode body(Call call) {
        ObjectNode b = mapper.createObjectNode();
        b.put("ncrNo", call.ncrNo());
        b.put("materialCode", call.materialCode());
        b.put("uom", call.uom());
        if (call.lotNo() != null) b.put("lotNo", call.lotNo());
        b.put("qty", call.qty());
        if (call.action() == Action.RELEASE || call.action() == Action.VOID) b.put("reason", call.reason());
        if ((call.action() == Action.RELEASE || call.action() == Action.SCRAP) && call.dispositionId() != null)
            b.put("dispositionId", call.dispositionId());
        return b;
    }

    /**
     * 结果分类（设计 §5）：
     * <ul>
     *   <li>2xx → 已记账，取 txnId；响应头 Idempotent-Replayed=true 为重放。</li>
     *   <li>400、422（除幂等键冲突）、409 insufficient-stock → 明确拒绝，库存确定未记账（certain）。</li>
     *   <li>422 idempotency-key-conflict 及其他 4xx → 不可重试，但不能证明未记账（uncertain）。</li>
     *   <li>401、403、404、408、429、5xx → 可重试（配置修复、服务恢复后可自愈）。</li>
     * </ul>
     */
    Outcome classify(Raw raw) {
        int s = raw.status();
        if (s >= 200 && s < 300) {
            Long txnId = null;
            try {
                JsonNode n = mapper.readTree(raw.body()).path("txnId");
                if (n.canConvertToLong()) txnId = n.asLong();
            } catch (Exception ignored) {
                // 响应体无法解析不影响“已记账”的判断，只是缺少流水号。
            }
            return new Booked(txnId, "true".equalsIgnoreCase(raw.replayed()));
        }
        String type = problemField(raw.body(), "type");
        String error = truncate("HTTP " + s + (type == null ? "" : " " + type) + optional(problemField(raw.body(), "detail")));
        if (s == 401 || s == 403 || s == 404 || s == 408 || s == 429 || s >= 500) return new Retryable(error);
        if (s == 400) return new Rejected(error, true);
        if (s == 422) return new Rejected(error, !"idempotency-key-conflict".equals(type));
        if (s == 409) return new Rejected(error, "insufficient-stock".equals(type));
        return new Rejected(error, false);
    }

    private String problemField(String body, String field) {
        try {
            JsonNode n = mapper.readTree(body).path(field);
            return n.isTextual() ? n.asText() : null;
        } catch (Exception e) {
            return null;
        }
    }

    private static String optional(String detail) { return detail == null ? "" : "：" + detail; }

    /** last_error 列最长 512 字符。 */
    static String truncate(String s) { return s == null || s.length() <= 512 ? s : s.substring(0, 512); }
}
