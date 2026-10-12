package com.smartmanufacturing.inventory;

import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 质检隔离库位 QC-HOLD 的内部接口（质检服务直连，不经网关）。授权在 {@link InventorySecurityConfiguration#qcHoldChain}：
 * 只接受 aud=inventory-service 且 scope 含 inventory:qc-hold 的服务令牌。
 * <p>三个接口都必须携带 Idempotency-Key（作用域为 client_id，质检侧固定为 qc:{ncrNo}:hold / :void / :disp:{id}:scrap|release），
 * 复用 {@link IdempotencyService}：同键同请求原样重放首次响应，同键不同请求 422。</p>
 */
@RestController
@RequestMapping("/inventory/qc-hold")
public class QcHoldController {
    private final StockService stock;
    private final IdempotencyService idempotency;

    public QcHoldController(StockService stock, IdempotencyService idempotency) {
        this.stock = stock; this.idempotency = idempotency;
    }

    /** 不良品入 QC-HOLD（流水 QC_HOLD_IN）。 */
    @PostMapping("/receipts")
    public ResponseEntity<String> receipt(@RequestHeader(value = "Idempotency-Key", required = false) String key,
                                          @RequestBody JsonNode body, Authentication auth) {
        QcHoldRequest r = QcHoldRequest.parse(QcHoldRequest.Kind.RECEIPT, body);
        String caller = CallerIdentity.of(auth);
        return StockController.respond(LockRetry.run(() -> idempotency.execute(caller, key, "qc-hold.receipt", r, 201,
            () -> stock.qcHoldIn(caller, r.materialCode(), r.uom(), r.lotNo(), r.qty(), r.ncrNo()))));
    }

    /** 隔离品放行出库（REWORK / CONCESSION / NCR_VOIDED，流水 QC_HOLD_RELEASE），不指定目标库位。 */
    @PostMapping("/releases")
    public ResponseEntity<String> release(@RequestHeader(value = "Idempotency-Key", required = false) String key,
                                          @RequestBody JsonNode body, Authentication auth) {
        QcHoldRequest r = QcHoldRequest.parse(QcHoldRequest.Kind.RELEASE, body);
        String caller = CallerIdentity.of(auth);
        return StockController.respond(LockRetry.run(() -> idempotency.execute(caller, key, "qc-hold.release", r, 201,
            () -> stock.qcHoldOut(caller, r.materialCode(), r.uom(), r.lotNo(), r.qty(), StockService.QC_HOLD_RELEASE, r.ncrNo()))));
    }

    /** 隔离品报废出库（流水 SCRAP）。 */
    @PostMapping("/scraps")
    public ResponseEntity<String> scrap(@RequestHeader(value = "Idempotency-Key", required = false) String key,
                                        @RequestBody JsonNode body, Authentication auth) {
        QcHoldRequest r = QcHoldRequest.parse(QcHoldRequest.Kind.SCRAP, body);
        String caller = CallerIdentity.of(auth);
        return StockController.respond(LockRetry.run(() -> idempotency.execute(caller, key, "qc-hold.scrap", r, 201,
            () -> stock.qcHoldOut(caller, r.materialCode(), r.uom(), r.lotNo(), r.qty(), StockService.SCRAP, r.ncrNo()))));
    }
}
