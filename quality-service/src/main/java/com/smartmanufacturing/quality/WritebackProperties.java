package com.smartmanufacturing.quality;

import org.springframework.boot.context.properties.ConfigurationProperties;
import java.time.Duration;

/**
 * 库存回写配置（mes.quality.writeback.*，设计见 docs/quality-inventory-writeback-design.md §5）。
 * <ul>
 *   <li>retryWindow：人工重试窗口，按任务创建时间计算，默认 P6D。<b>必须小于库存幂等记录保留期 7 天</b>：
 *       超过保留期后库存已不认识原幂等键，重放会重复记账；启动时校验，不满足直接失败。</li>
 *   <li>interval / batchSize：调度间隔（默认 2s）与每批领取上限（默认 50）。</li>
 *   <li>maxAttempts / initialBackoff / maxBackoff：可重试错误按指数退避（2s 起、上限 5min），最多 20 次后置 FAILED。</li>
 *   <li>lease：领取后的执行租约（默认 60s，须大于单次调用超时）；进程在调用中途崩溃时，租约到期后任务可被重新领取。</li>
 *   <li>retention：DONE / CANCELLED / RESOLVED_MANUALLY 任务的保留期（默认 P30D），随 03:47 清理任务删除。</li>
 *   <li>client：调用库存的服务身份与地址，见 {@link Client}。</li>
 * </ul>
 */
@ConfigurationProperties(prefix = "mes.quality.writeback")
public record WritebackProperties(Duration retryWindow, Duration interval, Integer batchSize, Integer maxAttempts,
                                  Duration initialBackoff, Duration maxBackoff, Duration lease, Duration retention, Client client) {
    /** 库存服务幂等记录保留期（库存服务固定 7 天，见 inventory IdempotencyRecordCleaner），重试窗口必须严格小于它。 */
    public static final Duration INVENTORY_IDEMPOTENCY_RETENTION = Duration.ofDays(7);

    public WritebackProperties {
        if (retryWindow == null) retryWindow = Duration.ofDays(6);
        if (interval == null) interval = Duration.ofSeconds(2);
        if (batchSize == null) batchSize = 50;
        if (maxAttempts == null) maxAttempts = 20;
        if (initialBackoff == null) initialBackoff = Duration.ofSeconds(2);
        if (maxBackoff == null) maxBackoff = Duration.ofMinutes(5);
        if (lease == null) lease = Duration.ofSeconds(60);
        if (retention == null) retention = Duration.ofDays(30);
        if (client == null) client = new Client(null, null, null, null, null, null, null, null, null, null);
        if (retryWindow.isNegative() || retryWindow.isZero() || retryWindow.compareTo(INVENTORY_IDEMPOTENCY_RETENTION) >= 0)
            throw new IllegalArgumentException("mes.quality.writeback.retry-window=" + retryWindow
                + " 必须大于 0 且小于库存幂等记录保留期 " + INVENTORY_IDEMPOTENCY_RETENTION + "，否则超期重放可能重复记账");
        if (batchSize <= 0 || maxAttempts <= 0) throw new IllegalArgumentException("mes.quality.writeback.batch-size / max-attempts 必须为正");
    }

    /**
     * 服务身份（OAuth2 client_credentials，scope=inventory:qc-hold，aud=inventory-service）与地址。
     * inventoryUrl / authUrl 为空时经 Nacos 服务发现直连 inventory-service / auth-service（不经网关，网关不转发 /api/inventory/qc-hold/**）；
     * 非空时直连固定地址，仅供本地调试。明文密钥只经环境变量 QUALITY_CLIENT_SECRET 注入。
     */
    public record Client(String clientId, String clientSecret, String scope, String inventoryUrl, String authUrl,
                         String inventoryService, String authService, Duration connectTimeout, Duration readTimeout,
                         Duration tokenRefreshSkew) {
        public Client {
            if (clientId == null || clientId.isBlank()) clientId = "quality-service";
            if (scope == null || scope.isBlank()) scope = "inventory:qc-hold";
            if (inventoryService == null || inventoryService.isBlank()) inventoryService = "inventory-service";
            if (authService == null || authService.isBlank()) authService = "auth-service";
            if (connectTimeout == null) connectTimeout = Duration.ofSeconds(2);
            if (readTimeout == null) readTimeout = Duration.ofSeconds(5);
            // 令牌剩余有效期小于该值即刷新（设计 §2：< 60s 刷新）。
            if (tokenRefreshSkew == null) tokenRefreshSkew = Duration.ofSeconds(60);
        }
        /** 是否已配置密钥；未配置（local/test）时调度器不调用库存，任务保持 PENDING。 */
        public boolean configured() { return clientSecret != null && !clientSecret.isBlank(); }
        String inventoryBase() { return blank(inventoryUrl) ? "http://" + inventoryService : inventoryUrl; }
        String authBase() { return blank(authUrl) ? "http://" + authService : authUrl; }
        private static boolean blank(String s) { return s == null || s.isBlank(); }
    }
}
