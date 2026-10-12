package com.smartmanufacturing.workorder;
import org.springframework.boot.context.properties.ConfigurationProperties;
import java.time.Duration;

/**
 * 库存客户端配置（manufacturing.workorder.inventory.*）。
 * <ul>
 *   <li>inventoryUrl / authUrl 为空时经 Nacos 服务发现（Spring Cloud LoadBalancer）调用 inventory-service / auth-service，
 *       不经网关（网关不放行预留接口）；非空时直连固定地址，仅供本地与集成测试使用。</li>
 *   <li>clientId / clientSecret：OAuth2 client_credentials 服务身份；密钥只经环境变量 WORKORDER_CLIENT_SECRET 注入，不进 Nacos。</li>
 *   <li>connectTimeout / readTimeout：单次调用超时，保持较短，避免释放作业持锁过久。</li>
 * </ul>
 */
@ConfigurationProperties(prefix = "manufacturing.workorder.inventory")
public record InventoryClientProperties(String inventoryUrl, String authUrl, String inventoryService, String authService,
                                        String clientId, String clientSecret, Duration connectTimeout, Duration readTimeout,
                                        Duration tokenRefreshSkew) {
    public InventoryClientProperties {
        if (inventoryService == null || inventoryService.isBlank()) inventoryService = "inventory-service";
        if (authService == null || authService.isBlank()) authService = "auth-service";
        if (clientId == null || clientId.isBlank()) clientId = "workorder-service";
        if (connectTimeout == null) connectTimeout = Duration.ofSeconds(2);
        if (readTimeout == null) readTimeout = Duration.ofSeconds(3);
        if (tokenRefreshSkew == null) tokenRefreshSkew = Duration.ofSeconds(60);
    }
    /** 库存服务基地址：固定地址优先，否则为服务发现地址 http://{服务名}。 */
    String inventoryBase() { return blank(inventoryUrl) ? "http://" + inventoryService : inventoryUrl; }
    /** 认证服务基地址。 */
    String authBase() { return blank(authUrl) ? "http://" + authService : authUrl; }
    /** 是否需要负载均衡（任一地址未固定时）。 */
    boolean discovery() { return blank(inventoryUrl) || blank(authUrl); }
    private static boolean blank(String s) { return s == null || s.isBlank(); }
}
