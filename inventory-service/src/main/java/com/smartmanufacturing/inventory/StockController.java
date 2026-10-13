package com.smartmanufacturing.inventory;

import com.smartmanufacturing.inventory.InventoryDtos.*;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import java.util.List;

/**
 * 库位、批次、现存量与出入库流水的 HTTP 入口（经网关为 /api/inventory/**，网关 StripPrefix=1 后为 /inventory/**）。
 * 面向用户令牌：读接口需 inventory:read，写接口需 inventory:write。出入库必须携带 Idempotency-Key。
 * 响应为业务对象本身，错误为 RFC 7807，与预留接口契约风格一致。
 */
@RestController
@RequestMapping("/inventory")
@PreAuthorize("hasAuthority('inventory:read')")
public class StockController {
    private final LocationService locations;
    private final StockService stock;
    private final IdempotencyService idempotency;

    public StockController(LocationService locations, StockService stock, IdempotencyService idempotency) {
        this.locations = locations; this.stock = stock; this.idempotency = idempotency;
    }

    /** 新建库位（主数据性质，按编码唯一，不要求幂等键）。 */
    @PreAuthorize("hasAuthority('inventory:write')")
    @PostMapping("/locations")
    public ResponseEntity<Location> createLocation(@Valid @RequestBody CreateLocation request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(locations.create(request));
    }

    @GetMapping("/locations")
    public List<Location> listLocations() { return locations.list(); }

    @GetMapping("/lots")
    public List<Lot> lots(@RequestParam String materialCode) { return stock.lots(materialCode); }

    @GetMapping("/stock")
    public List<Stock> stock(@RequestParam String materialCode, @RequestParam(required = false) String locationCode) {
        return stock.stock(materialCode, locationCode);
    }

    @GetMapping("/stock/availability")
    public Availability availability(@RequestParam String materialCode) { return stock.availability(materialCode); }

    @GetMapping("/ledger")
    public List<StockTxn> ledger(@RequestParam String materialCode, @RequestParam(required = false) String lotNo,
                                 @RequestParam(defaultValue = "50") int limit) {
        return stock.ledger(materialCode, lotNo, limit);
    }

    /** 入库：幂等，首次 201，同键同内容重放返回首次响应原文。 */
    @PreAuthorize("hasAuthority('inventory:write')")
    @PostMapping("/inbound")
    public ResponseEntity<String> inbound(@RequestHeader(value = "Idempotency-Key", required = false) String key,
                                          @Valid @RequestBody Movement request, Authentication auth) {
        Movement normalized = normalize(request);
        String caller = CallerIdentity.of(auth);
        // 主事务开始前预建目标现存量行（独立短事务），主事务内只按主键加锁（#57）。
        stock.prepareRows(java.util.List.of(new StockService.RowKey(normalized.materialCode(), normalized.locationCode(), normalized.lotNo())));
        return respond(LockRetry.run(() -> idempotency.execute(caller, key, "inbound", normalized, 201,
            () -> stock.inbound(caller, normalized))));
    }

    /** 出库：幂等；不足或会侵占预留时返回 409 insufficient-stock。 */
    @PreAuthorize("hasAuthority('inventory:write')")
    @PostMapping("/outbound")
    public ResponseEntity<String> outbound(@RequestHeader(value = "Idempotency-Key", required = false) String key,
                                           @Valid @RequestBody Movement request, Authentication auth) {
        Movement normalized = normalize(request);
        String caller = CallerIdentity.of(auth);
        return respond(LockRetry.run(() -> idempotency.execute(caller, key, "outbound", normalized, 201,
            () -> stock.outbound(caller, normalized))));
    }

    /** 规范化后再计算摘要与落库：去除首尾空白，数量统一为最简十进制形式（"10" 与 "10.0" 视为同一请求）。 */
    static Movement normalize(Movement r) {
        String qty = Quantities.format(Quantities.parsePositive("qty", r.qty()));
        return new Movement(r.materialCode().strip(), r.uom().strip(), r.locationCode().strip(), r.lotNo().strip(), qty,
            r.refNo() == null ? null : r.refNo().strip());
    }

    /** 输出幂等执行器保存的响应原文；Idempotent-Replayed 头标明是否为重放（与工单服务一致）。 */
    static ResponseEntity<String> respond(IdempotencyService.StoredResponse r) {
        return ResponseEntity.status(r.status()).contentType(MediaType.APPLICATION_JSON)
            .header("Idempotent-Replayed", String.valueOf(r.replayed())).body(r.body());
    }
}
