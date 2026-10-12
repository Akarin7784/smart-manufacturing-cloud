package com.smartmanufacturing.workorder;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * 集成测试用的库存服务 + 认证服务替身（JDK HttpServer），按 docs/api/inventory-openapi.yaml 实现预留相关接口的最小子集：
 * <ul>
 *   <li>POST /auth/oauth2/token：校验 Basic 凭据，签发不透明令牌（expires_in=300）；</li>
 *   <li>POST /inventory/reservations、/{id}/renew、/{id}/release，GET /{id}：校验 Bearer 令牌与 Idempotency-Key，
 *       同键重放原样返回首次状态码与响应体；</li>
 *   <li>错误：物料编码 NEVER-STOCKED → 409 insufficient-stock（available=0）；uom 为 BAD-UOM → 422 uom-mismatch；
 *       CONSUMED 的预留释放 → 409 reservation-consumed；renewForbidden 时续约 → 403（非持有方）。</li>
 * </ul>
 * {@link #stop()} / {@link #start()} 在同一端口停启，模拟库存宕机与恢复（连接被拒，即网络错误）。
 */
final class InventoryStub {
    static final String SECRET = "it-secret";
    private final ObjectMapper mapper = new ObjectMapper();
    private final int port;
    private HttpServer server;
    final Map<String, ObjectNode> reservations = new ConcurrentHashMap<>();
    /** 每张预留单最后一次释放的原因。 */
    final Map<String, String> releaseReasons = new ConcurrentHashMap<>();
    /** 幂等记录：键 → [状态码, 响应体]。 */
    private final Map<String, Object[]> idempotency = new ConcurrentHashMap<>();
    /** 实际执行（非重放）的调用，形如 release:{Idempotency-Key}。 */
    final List<String> calls = new CopyOnWriteArrayList<>();
    volatile boolean renewForbidden;
    /** 非 null 时 release 返回该状态码（如 403、503）。 */
    volatile Integer releaseFailure;
    private final Map<String, Boolean> tokens = new ConcurrentHashMap<>();

    InventoryStub() {
        try (ServerSocket s = new ServerSocket(0)) { port = s.getLocalPort(); }
        catch (IOException e) { throw new IllegalStateException(e); }
    }

    String url() { return "http://localhost:" + port; }

    void start() {
        try {
            server = HttpServer.create(new InetSocketAddress("localhost", port), 0);
            server.createContext("/", this::handle);
            server.start();
        } catch (IOException e) { throw new IllegalStateException(e); }
    }

    void stop() { if (server != null) server.stop(0); server = null; }

    void reset() { reservations.clear(); releaseReasons.clear(); idempotency.clear(); calls.clear(); renewForbidden = false; releaseFailure = null; }

    private void handle(HttpExchange ex) throws IOException {
        try {
            String path = ex.getRequestURI().getPath();
            if (path.equals("/auth/oauth2/token")) { token(ex); return; }
            String auth = ex.getRequestHeaders().getFirst("Authorization");
            if (auth == null || !tokens.containsKey(auth.replace("Bearer ", ""))) { send(ex, 401, problem("about:blank", 401)); return; }
            String key = ex.getRequestHeaders().getFirst("Idempotency-Key");
            JsonNode body = ex.getRequestMethod().equals("POST") && ex.getRequestBody().available() >= 0 ? read(ex) : null;
            String[] p = path.split("/"); // ["", "inventory", "reservations", id?, action?]
            if (ex.getRequestMethod().equals("GET")) { ObjectNode r = reservations.get(p[3]); send(ex, r == null ? 404 : 200, r == null ? problem("about:blank", 404) : r.toString()); return; }
            if (key == null) { send(ex, 400, problem("validation-error", 400)); return; }
            String scope = path + "|" + key;
            Object[] prior = idempotency.get(scope);
            if (prior != null) { send(ex, (Integer) prior[0], (String) prior[1]); return; }
            Object[] result = p.length == 3 ? create(body) : p[4].equals("renew") ? renew(p[3]) : release(p[3], body);
            calls.add((p.length == 3 ? "reserve" : p[4]) + ":" + key);
            int status = (Integer) result[0];
            if (status / 100 == 2) idempotency.put(scope, result); // 与库存一致：只记录成功结果
            send(ex, status, (String) result[1]);
        } catch (RuntimeException e) {
            send(ex, 500, problem("about:blank", 500));
        }
    }

    private void token(HttpExchange ex) throws IOException {
        String basic = ex.getRequestHeaders().getFirst("Authorization");
        String expected = "Basic " + java.util.Base64.getEncoder().encodeToString(("workorder-service:" + SECRET).getBytes(StandardCharsets.UTF_8));
        if (!expected.equals(basic)) { send(ex, 401, "{\"error\":\"invalid_client\"}"); return; }
        String t = UUID.randomUUID().toString();
        tokens.put(t, true);
        send(ex, 200, "{\"access_token\":\"" + t + "\",\"token_type\":\"Bearer\",\"expires_in\":300,\"scope\":\"inventory:reserve\"}");
    }

    private Object[] create(JsonNode req) {
        ArrayNode shortages = mapper.createArrayNode();
        for (JsonNode l : req.path("lines")) {
            if (l.path("uom").asText().equals("BAD-UOM")) return new Object[]{422, problem("uom-mismatch", 422)};
            if (l.path("materialCode").asText().equals("NEVER-STOCKED"))
                shortages.addObject().put("materialCode", "NEVER-STOCKED").put("uom", l.path("uom").asText()).put("requested", l.path("qty").asText())
                    .put("available", "0").put("shortage", l.path("qty").asText()).putArray("sourceLineIds").add(l.path("sourceLineId").asText());
        }
        if (!shortages.isEmpty()) {
            ObjectNode pr = (ObjectNode) json(problem("insufficient-stock", 409));
            pr.set("shortages", shortages);
            return new Object[]{409, pr.toString()};
        }
        String id = "RSV-" + UUID.randomUUID();
        ObjectNode r = mapper.createObjectNode().put("reservationId", id).put("workOrderNo", req.path("workOrderNo").asText())
            .put("status", "ACTIVE").put("expiresAt", Instant.now().plusSeconds(900).toString());
        ArrayNode lines = r.putArray("lines");
        int n = 0;
        for (JsonNode l : req.path("lines"))
            lines.addObject().put("lineId", id + "-L" + (++n)).put("sourceLineId", l.path("sourceLineId").asText()).put("materialCode", l.path("materialCode").asText())
                .put("uom", l.path("uom").asText()).put("qty", l.path("qty").asText()).put("reservedQty", l.path("qty").asText()).put("consumedQty", "0").put("status", "OPEN");
        reservations.put(id, r);
        return new Object[]{201, r.toString()};
    }

    private Object[] renew(String id) {
        ObjectNode r = reservations.get(id);
        if (r == null) return new Object[]{404, problem("about:blank", 404)};
        if (renewForbidden) return new Object[]{403, problem("about:blank", 403)};
        if (!r.path("status").asText().equals("ACTIVE")) return new Object[]{410, problem("reservation-" + r.path("status").asText().toLowerCase(), 410)};
        r.put("expiresAt", Instant.now().plusSeconds(900).toString());
        return new Object[]{200, r.toString()};
    }

    private Object[] release(String id, JsonNode req) {
        if (releaseFailure != null) return new Object[]{releaseFailure, problem("about:blank", releaseFailure)};
        ObjectNode r = reservations.get(id);
        if (r == null) return new Object[]{404, problem("about:blank", 404)};
        String s = r.path("status").asText();
        if (s.equals("CONSUMED")) return new Object[]{409, problem("reservation-consumed", 409)};
        if (s.equals("ACTIVE")) { r.put("status", "RELEASED"); releaseReasons.put(id, req == null ? "CALLER_REQUEST" : req.path("reason").asText("CALLER_REQUEST")); }
        return new Object[]{200, r.toString()};
    }

    private String problem(String type, int status) { return "{\"type\":\"" + type + "\",\"title\":\"t\",\"status\":" + status + ",\"detail\":\"stub\"}"; }
    private JsonNode json(String s) { try { return mapper.readTree(s); } catch (IOException e) { throw new IllegalStateException(e); } }
    private JsonNode read(HttpExchange ex) throws IOException {
        byte[] b = ex.getRequestBody().readAllBytes();
        return b.length == 0 ? null : mapper.readTree(b);
    }
    private static void send(HttpExchange ex, int status, String body) throws IOException {
        byte[] b = body.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().set("Content-Type", status / 100 == 2 ? "application/json" : "application/problem+json");
        ex.sendResponseHeaders(status, b.length);
        try (var os = ex.getResponseBody()) { os.write(b); }
    }
}
