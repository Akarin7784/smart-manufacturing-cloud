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
 *   <li>401：作废缓存令牌重取一次再调；仍 401 直接 FAILED 并计鉴权失败。</li>
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

    /** 库存 qc-hold 接口约定的三种 404 type（库位 / 物料 / 批次不存在），确定未记账、重试无益。 */
    static final java.util.Set<String> NOT_FOUND_TYPES = java.util.Set.of("location-not-found", "material-not-found", "lot-not-found");

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
            // 令牌端点 4xx：凭据配置问题，不重试；未调用库存，确定未记账。5xx / 不可达：退避重试。
            return e.rejected ? new Rejected(truncate(e.getMessage()), true, Signal.TOKEN_AUTH)
                : new Retryable(truncate(e.getMessage()), e.server ? RetryReason.SERVER : RetryReason.NETWORK);
        } catch (RestClientException e) {
            // 连接失败、读超时等：请求可能已到达库存并记账，结果不确定，按可重试处理（同一幂等键重放安全）。
            return new Retryable(truncate("调用库存失败：" + e.getMessage()), RetryReason.NETWORK);
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
     * 结果分类（设计 §5，#55 拍板）：
     * <ul>
     *   <li>2xx → 已记账，取 txnId；响应头 Idempotent-Replayed=true 为重放；缺 txnId 仍算已记账，另计契约不一致。</li>
     *   <li>429、503 及其他 5xx、408 → 退避重试（网络错误在 {@link #call} 中同样退避）。</li>
     *   <li>401（已刷新令牌重试一次）、403 → FAILED，确定未记账，计鉴权失败。</li>
     *   <li>404 location-not-found / material-not-found / lot-not-found → FAILED，确定未记账；其他 404（gateway-not-found、about:blank、
 *       未定义 type，多为路由或部署问题）→ 退避重试，次数由 max-unknown-404-retries 限制，超限 FAILED 并计契约不一致（执行器处理）。</li>
     *   <li>409 insufficient-stock、422 uom-mismatch → FAILED，确定未记账。</li>
     *   <li>400 validation-error → FAILED，确定未记账；说明请求构造有误，计契约不一致（与 workorder 同名指标口径一致）。</li>
     *   <li>422 idempotency-key-conflict → FAILED，<b>不能</b>证明未记账（首次请求可能已记账，VOID 不自动取消），计契约不一致。</li>
     *   <li>其他未约定的状态或 type → FAILED（不确定），计契约不一致。</li>
     * </ul>
     */
    Outcome classify(Raw raw) {
        int s = raw.status();
        if (s >= 200 && s < 300) {
            Long txnId = null;
            try {
                JsonNode n = mapper.readTree(raw.body()).path("txnId");
                if (n.isIntegralNumber()) txnId = n.asLong();
            } catch (Exception ignored) {
                // 响应体无法解析不影响“已记账”的判断，只是缺少流水号（计契约不一致）。
            }
            return new Booked(txnId, "true".equalsIgnoreCase(raw.replayed()), txnId == null);
        }
        String type = problemField(raw.body(), "type");
        String error = truncate("HTTP " + s + (type == null ? "" : " " + type) + optional(problemField(raw.body(), "detail")));
        if (s == 429) return new Retryable(error, RetryReason.TOO_MANY);
        if (s >= 500) return new Retryable(error, RetryReason.SERVER);
        if (s == 408) return new Retryable(error, RetryReason.NETWORK);
        if (s == 401 || s == 403) return new Rejected(error, true, Signal.AUTH);
        if (s == 404) return type != null && NOT_FOUND_TYPES.contains(type) ? new Rejected(error, true, Signal.NONE) : new Retryable(error, RetryReason.NOT_FOUND_UNKNOWN);
        if (s == 409 && "insufficient-stock".equals(type)) return new Rejected(error, true, Signal.NONE);
        if (s == 422 && "uom-mismatch".equals(type)) return new Rejected(error, true, Signal.NONE);
        if (s == 400) return new Rejected(error, true, Signal.CONTRACT);
        if (s == 422 && "idempotency-key-conflict".equals(type)) return new Rejected(error, false, Signal.CONTRACT);
        return new Rejected(error, false, Signal.CONTRACT);
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
