package com.smartmanufacturing.quality;

import com.smartmanufacturing.quality.NcrDtos.*;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Supplier;

/**
 * NCR 接口（网关 /api/quality/** 去掉 /api 前缀后转发到本服务的 /quality/**）。
 * 查询需 quality:read，写入需 quality:write；所有写入经 {@link IdempotencyService} 执行（Idempotency-Key 必填，作用域为调用方 sub）。
 * 写入结果计入 mes_quality_api_total{result}。
 */
@RestController
@RequestMapping("/quality")
public class NcrController {
    private final NcrService ncrs;
    private final IdempotencyService idempotency;
    private final QualityMetrics metrics;

    public NcrController(NcrService ncrs, IdempotencyService idempotency, QualityMetrics metrics) {
        this.ncrs = ncrs; this.idempotency = idempotency; this.metrics = metrics;
    }

    @GetMapping("/ncrs")
    @PreAuthorize("hasAuthority('quality:read')")
    public Page<Ncr> list(@RequestParam(required = false) String status, @RequestParam(required = false) String workOrderNo,
                          @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "20") int size) {
        return ncrs.list(status, workOrderNo, page, size);
    }

    @GetMapping("/ncrs/{ncrNo}")
    @PreAuthorize("hasAuthority('quality:read')")
    public Ncr get(@PathVariable String ncrNo) { return ncrs.get(ncrNo); }

    @PostMapping("/ncrs/{ncrNo}/review")
    @PreAuthorize("hasAuthority('quality:write')")
    public ResponseEntity<String> review(@PathVariable String ncrNo, @RequestHeader(value = "Idempotency-Key", required = false) String key,
                                         Authentication auth) {
        return write(auth, key, "ncr.review", Map.of("ncrNo", ncrNo), 200, () -> ncrs.review(ncrNo));
    }

    @PostMapping("/ncrs/{ncrNo}/dispositions")
    @PreAuthorize("hasAuthority('quality:write')")
    public ResponseEntity<String> dispose(@PathVariable String ncrNo, @RequestHeader(value = "Idempotency-Key", required = false) String key,
                                          @RequestBody CreateDisposition request, Authentication auth) {
        String operator = CallerIdentity.of(auth);
        Map<String, Object> input = new LinkedHashMap<>();
        input.put("ncrNo", ncrNo);
        input.put("request", request);
        return write(auth, key, "ncr.dispose", input, 201, () -> ncrs.dispose(ncrNo, operator, request));
    }

    @PostMapping("/ncrs/{ncrNo}/close")
    @PreAuthorize("hasAuthority('quality:write')")
    public ResponseEntity<String> close(@PathVariable String ncrNo, @RequestHeader(value = "Idempotency-Key", required = false) String key,
                                        Authentication auth) {
        return write(auth, key, "ncr.close", Map.of("ncrNo", ncrNo), 200, () -> ncrs.close(ncrNo));
    }

    @GetMapping("/pending-reversals")
    @PreAuthorize("hasAuthority('quality:read')")
    public Page<PendingReversal> pendingReversals(@RequestParam(required = false) String status,
                                                  @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "20") int size) {
        return ncrs.pendingReversals(status, page, size);
    }

    @GetMapping("/conflicts")
    @PreAuthorize("hasAuthority('quality:read')")
    public Page<Conflict> conflicts(@RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "20") int size) {
        return ncrs.conflicts(page, size);
    }

    /**
     * 幂等执行写操作并计数。操作名带 NCR 编号的路径参数已放入摘要输入，同一个键挪用到另一张 NCR 或另一种操作返回 422。
     * 死锁被选为牺牲者时整笔回滚，由 {@link LockRetry} 重试。
     */
    private ResponseEntity<String> write(Authentication auth, String key, String operation, Object input, int status, Supplier<Object> action) {
        try {
            IdempotencyService.StoredResponse r = LockRetry.run(() ->
                idempotency.execute(CallerIdentity.of(auth), key, operation, input, status, action));
            metrics.api(r.replayed() ? "replay" : "ok");
            return ResponseEntity.status(r.status()).contentType(MediaType.APPLICATION_JSON).body(r.body());
        } catch (ProblemException e) {
            metrics.api(e.type().metricValue());
            throw e;
        } catch (RuntimeException e) {
            metrics.api("error");
            throw e;
        }
    }
}
