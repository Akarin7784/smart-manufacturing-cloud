package com.smartmanufacturing.workorder;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.smartmanufacturing.api.workorder.MaterialShortage;
import com.smartmanufacturing.api.workorder.ReservationLineView;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 物料预留端口的生产实现（manufacturing.workorder.material-reservation=inventory）：按 docs/api/inventory-openapi.yaml 调用库存服务。
 * <ul>
 *   <li>地址：经 Nacos 服务发现直连 inventory-service 的服务内路径 /inventory/reservations/**（不经网关、没有 /api 前缀）。</li>
 *   <li>身份：每次调用携带 {@link ServiceTokenProvider} 缓存的服务令牌；库存返回 401 时丢弃缓存令牌，下次重新申请。</li>
 *   <li>错误：按 Problem type 穷举映射（见 {@link #toException}），HTTP 401/403/404/5xx 与网络错误按状态处理。</li>
 *   <li>数量：十进制字符串，去掉尾随 0 后发送（契约 Quantity 最多 6 位小数）；响应数量统一为 6 位小数存入本地镜像。</li>
 * </ul>
 * 本类无状态（令牌缓存除外），不开启本地事务；事务边界由调用方控制（远程调用都在本地事务之外，释放作业除外，见其说明）。
 */
public class InventoryReservationClient implements MaterialReservationPort {
    private static final Logger LOG = LoggerFactory.getLogger(InventoryReservationClient.class);
    private static final String BASE = "/inventory/reservations";
    private final RestClient http;
    private final ServiceTokenProvider tokens;
    private final ObjectMapper mapper;
    private final InventoryClientMetrics metrics;

    InventoryReservationClient(RestClient http, ServiceTokenProvider tokens, ObjectMapper mapper, InventoryClientMetrics metrics) {
        this.http = http; this.tokens = tokens; this.mapper = mapper; this.metrics = metrics;
    }

    /** 一次 HTTP 调用的结果：状态码与已解析的 JSON 响应体（无响应体时为 null）。 */
    record Response(int status, JsonNode body) {}

    @Override public Reservation reserve(String key, String orderId, List<ReservationLineView> lines) {
        List<Map<String, Object>> body = new ArrayList<>();
        // sourceLineId = {工单号}:{物料编码}：本服务按物料汇总叶子件（同一物料一行），物料编码在单内唯一
        for (ReservationLineView l : lines) {
            Map<String, Object> line = new LinkedHashMap<>();
            line.put("materialCode", l.materialCode());
            line.put("qty", quantity(l.quantity()));
            line.put("uom", l.uom());
            line.put("sourceLineId", orderId + ":" + l.materialCode());
            body.add(line);
        }
        Response r = call(HttpMethod.POST, BASE, key, Map.of("workOrderNo", orderId, "lines", body), "reserve", null);
        if (r.status() != 201 && r.status() != 200) throw unexpected("reserve", r);
        return reservation(r.body());
    }

    @Override public Instant renew(String key, String reservationId) {
        Response r = call(HttpMethod.POST, BASE + "/" + reservationId + "/renew", key, null, "renew", reservationId);
        if (r.status() != 200) throw unexpected("renew", r);
        // 续约不发事件：以响应中的 expiresAt 为准
        return Instant.parse(r.body().path("expiresAt").asText());
    }

    @Override public String release(String key, String reservationId, ReleaseReason reason) {
        Response r = call(HttpMethod.POST, BASE + "/" + reservationId + "/release", key, Map.of("reason", reason.name()), "release", reservationId);
        if (r.status() / 100 != 2) throw unexpected("release", r);
        String status = r.body() == null ? null : r.body().path("status").asText(null);
        // 释放幂等：RELEASED（本次或之前已释放）与 EXPIRED（占用已由过期归还）都算成功
        if ("RELEASED".equals(status) || "EXPIRED".equals(status)) return status;
        throw new InventoryContractException(null, "释放返回 2xx 但预留状态为 " + status);
    }

    /** 查询预留单（运维排查与测试使用）。 */
    JsonNode get(String reservationId) {
        Response r = call(HttpMethod.GET, BASE + "/" + reservationId, null, null, "get", reservationId);
        if (r.status() != 200) throw unexpected("get", r);
        return r.body();
    }

    // ------------------------------------------------------------------ HTTP 与错误映射

    /**
     * 发起调用：2xx 原样返回；非 2xx 统一经 {@link #toException} 转为异常。网络错误、服务发现无实例转为 {@link InventoryUnavailableException}。
     */
    private Response call(HttpMethod method, String path, String key, Object body, String op, String reservationId) {
        Response r;
        try {
            RestClient.RequestBodySpec spec = http.method(method).uri(path).headers(tokens::apply).accept(MediaType.APPLICATION_JSON, MediaType.APPLICATION_PROBLEM_JSON);
            if (key != null) spec.header("Idempotency-Key", key);
            if (body != null) spec.contentType(MediaType.APPLICATION_JSON).body(body);
            r = spec.exchange((req, res) -> new Response(res.getStatusCode().value(), read(res.getBody())), true);
        } catch (InventoryAuthException e) {
            metrics.authFailure("token"); // 申请服务令牌被拒
            throw e;
        } catch (InventoryUnavailableException e) {
            throw e; // 认证服务不可用
        } catch (RestClientException | IllegalStateException e) {
            throw new InventoryUnavailableException("库存服务不可达（" + op + "）：" + e.getMessage(), e);
        }
        if (r.status() / 100 == 2) return r;
        throw toException(op, reservationId, r);
    }

    private JsonNode read(InputStream in) throws IOException {
        byte[] bytes = in.readAllBytes();
        if (bytes.length == 0) return null;
        try { return mapper.readTree(bytes); }
        catch (IOException e) { return null; } // 非 JSON（如网关 / 代理错误页），按状态码处理
    }

    /**
     * 把库存的错误响应映射为本服务异常。业务错误按契约的 9 种 type 穷举处理：
     * <ul>
     *   <li>insufficient-stock（409）→ {@link MaterialShortageException}，缺口逐行原样带回（物料从未入库时 available=0）；</li>
     *   <li>uom-mismatch（422）→ 本服务 422 uom-mismatch（BOM 单位与库存已登记的现存量单位不一致，需要修主数据）；</li>
     *   <li>reservation-expired / reservation-released（410）→ {@link ReservationGoneException}（续约无法挽回，转预留过期流程）；</li>
     *   <li>reservation-consumed（409）→ {@link ReservationConsumedException}（不可释放，调用方停止重试）；</li>
     *   <li>validation-error（400）、idempotency-key-conflict（422）、over-consume（422）、line-closed（409）→
     *       {@link InventoryContractException}：本服务目前不调用扣减接口，出现即说明请求构造有误，记 ERROR。</li>
     * </ul>
     * 非业务错误：401 / 403 → {@link InventoryAuthException}（401 同时丢弃缓存令牌；续约 403 表示本服务不是持有方）；
     * 404 → 预留单不存在（续约按已失效处理）；5xx → {@link InventoryUnavailableException}。
     */
    RuntimeException toException(String op, String reservationId, Response r) {
        String type = r.body() == null ? null : r.body().path("type").asText(null);
        String detail = r.body() == null ? "" : r.body().path("detail").asText("");
        InventoryProblemType t = InventoryProblemType.of(type);
        if (t != null) {
            return switch (t) {
                case INSUFFICIENT_STOCK -> new MaterialShortageException(shortages(r.body()));
                case UOM_MISMATCH -> WorkOrderProblemType.UOM_MISMATCH.exception("物料计量单位与库存现存量单位不一致：" + detail,
                    Map.of("source", "inventory", "detail", detail));
                case RESERVATION_EXPIRED, RESERVATION_RELEASED -> new ReservationGoneException(reservationId);
                case RESERVATION_CONSUMED -> new ReservationConsumedException(reservationId);
                case VALIDATION_ERROR, IDEMPOTENCY_KEY_CONFLICT, OVER_CONSUME, LINE_CLOSED -> {
                    LOG.error("库存 {} 返回 {} {}：{}（请求构造有误或契约不一致）", op, r.status(), t.type(), detail);
                    yield new InventoryContractException(t.type(), op + " 返回 " + t.type());
                }
            };
        }
        int s = r.status();
        if (s == 401 || s == 403) {
            if (s == 401) tokens.invalidate();
            metrics.authFailure(op);
            return new InventoryAuthException(s, op + (s == 403 && "renew".equals(op) ? "：只有持有该预留的服务身份可以续约" : ""));
        }
        if (s == 404 && reservationId != null)
            return "renew".equals(op) ? new ReservationGoneException(reservationId) : new ReservationNotFoundException(reservationId);
        if (s >= 500) return new InventoryUnavailableException("库存服务错误（" + op + "，HTTP " + s + "）", null);
        LOG.error("库存 {} 返回未约定的响应：HTTP {} type={}", op, s, type);
        return new InventoryContractException(type, op + " 返回 HTTP " + s);
    }

    private RuntimeException unexpected(String op, Response r) {
        return new InventoryContractException(null, op + " 返回未约定的状态 " + r.status());
    }

    private static List<MaterialShortage> shortages(JsonNode body) {
        List<MaterialShortage> list = new ArrayList<>();
        for (JsonNode s : body.path("shortages"))
            list.add(new MaterialShortage(s.path("materialCode").asText(), dec(s.path("requested")), dec(s.path("available")), s.path("uom").asText()));
        return list;
    }

    private static Reservation reservation(JsonNode body) {
        if (body == null) throw new InventoryContractException(null, "预留响应缺少响应体");
        List<ReservationLineView> lines = new ArrayList<>();
        for (JsonNode l : body.path("lines"))
            lines.add(new ReservationLineView(l.path("lineId").asText(), l.path("materialCode").asText(),
                WorkOrderReleaseService.scale6(dec(l.path("qty"))), l.path("uom").asText()));
        return new Reservation(body.path("reservationId").asText(), Instant.parse(body.path("expiresAt").asText()), List.copyOf(lines));
    }

    private static BigDecimal dec(JsonNode n) { return n.isMissingNode() || n.isNull() ? BigDecimal.ZERO : new BigDecimal(n.asText()); }

    /** 契约 Quantity：非科学计数法、去尾随 0。 */
    static String quantity(BigDecimal q) {
        String s = q.stripTrailingZeros().toPlainString();
        return s;
    }
}
