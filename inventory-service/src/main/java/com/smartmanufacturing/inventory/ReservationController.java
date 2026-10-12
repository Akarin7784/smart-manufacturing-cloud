package com.smartmanufacturing.inventory;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.smartmanufacturing.inventory.ReservationDtos.*;
import jakarta.validation.Valid;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * 预留接口 HTTP 入口：服务内路径 /inventory/reservations/**，只能由工单服务直连调用（网关对 /api/inventory/reservations/** 返回 404）。
 * 认证与授权由 {@link InventorySecurityConfiguration} 的预留过滤链完成（服务令牌，aud=inventory-service，scope=inventory:reserve）。
 * 修改类接口必须带 Idempotency-Key，作用域为调用方 client_id；重放原样返回首次状态码与响应体。
 */
@RestController
@RequestMapping("/inventory/reservations")
public class ReservationController {
    private final ReservationService reservations;
    private final IdempotencyService idempotency;
    private final ObjectMapper mapper;
    private final InventoryMetrics metrics;

    public ReservationController(ReservationService reservations, IdempotencyService idempotency, ObjectMapper mapper, InventoryMetrics metrics) {
        this.reservations = reservations; this.idempotency = idempotency; this.mapper = mapper; this.metrics = metrics;
    }

    /** 整单预留：首次 201 并带 Location（直连地址 /inventory/reservations/{id}，不带网关前缀）；重放同样带 Location（从首次响应体解析）。 */
    @PostMapping
    public ResponseEntity<String> create(@RequestHeader(value = "Idempotency-Key", required = false) String key,
                                         @Valid @RequestBody CreateReservation request, Authentication auth) {
        String caller = CallerIdentity.of(auth);
        IdempotencyService.StoredResponse r;
        try {
            CreateReservation normalized = normalize(request);
            r = LockRetry.run(() -> idempotency.execute(caller, key, "reservation.create", normalized, 201,
                () -> reservations.create(caller, normalized)));
        } catch (ProblemException e) {
            // 业务错误按 ProblemType 计数（下划线写法，如 insufficient_stock）。
            metrics.reservation(e.type().value().replace('-', '_'));
            throw e;
        } catch (RuntimeException e) {
            metrics.reservation("error");
            throw e;
        }
        metrics.reservation(r.replayed() ? "replay" : "ok");
        ResponseEntity<String> response = StockController.respond(r);
        try {
            String id = mapper.readTree(r.body()).path("reservationId").asText();
            return ResponseEntity.status(response.getStatusCode()).headers(response.getHeaders())
                .header("Location", "/inventory/reservations/" + id).body(r.body());
        } catch (Exception e) {
            return response;
        }
    }

    @GetMapping("/{reservationId}")
    public Reservation get(@PathVariable String reservationId) { return reservations.get(reservationId); }

    @PostMapping("/{reservationId}/renew")
    public ResponseEntity<String> renew(@PathVariable String reservationId,
                                        @RequestHeader(value = "Idempotency-Key", required = false) String key, Authentication auth) {
        String caller = CallerIdentity.of(auth);
        return StockController.respond(LockRetry.run(() -> idempotency.execute(caller, key, "reservation.renew",
            Map.of("reservationId", reservationId), 200, () -> reservations.renew(caller, reservationId))));
    }

    @PostMapping("/{reservationId}/release")
    public ResponseEntity<String> release(@PathVariable String reservationId,
                                          @RequestHeader(value = "Idempotency-Key", required = false) String key,
                                          @RequestBody(required = false) ReleaseRequest body, Authentication auth) {
        String caller = CallerIdentity.of(auth);
        ReleaseReason reason = body == null || body.reason() == null ? ReleaseReason.CALLER_REQUEST : body.reason();
        return StockController.respond(LockRetry.run(() -> idempotency.execute(caller, key, "reservation.release",
            Map.of("reservationId", reservationId, "reason", reason), 200, () -> reservations.release(reservationId, reason))));
    }

    @PostMapping("/{reservationId}/lines/{lineId}/consume")
    public ResponseEntity<String> consume(@PathVariable String reservationId, @PathVariable String lineId,
                                          @RequestHeader(value = "Idempotency-Key", required = false) String key,
                                          @Valid @RequestBody ConsumeRequest request, Authentication auth) {
        String caller = CallerIdentity.of(auth);
        ConsumeRequest normalized = new ConsumeRequest(Quantities.format(Quantities.parsePositive("qty", request.qty())), request.lotNo().strip());
        Map<String, Object> input = new TreeMap<>(Map.of("reservationId", reservationId, "lineId", lineId, "body", normalized));
        return StockController.respond(LockRetry.run(() -> idempotency.execute(caller, key, "reservation.consume", input, 200,
            () -> reservations.consume(reservationId, lineId, normalized))));
    }

    /** 规范化：去除首尾空白、数量统一为最简十进制，使等价请求得到相同摘要；行顺序保留（行号即提交顺序）。 */
    static CreateReservation normalize(CreateReservation r) {
        List<CreateLine> lines = r.lines().stream().map(l -> new CreateLine(l.materialCode().strip(),
            Quantities.format(Quantities.parsePositive("lines.qty", l.qty())), l.uom().strip(), l.sourceLineId().strip())).toList();
        return new CreateReservation(r.workOrderNo().strip(), lines);
    }
}
