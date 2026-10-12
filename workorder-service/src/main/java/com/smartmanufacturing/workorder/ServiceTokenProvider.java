package com.smartmanufacturing.workorder;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;
import java.time.Clock;
import java.time.Instant;

/**
 * 服务身份令牌（OAuth2 client_credentials，scope=inventory:reserve）：向 auth-service 的 /auth/oauth2/token 申请并缓存。
 * 按契约必须缓存令牌、临近过期（默认提前 60 秒）才刷新，不得每次请求都重新申请；库存返回 401 时调用 {@link #invalidate} 强制下次刷新。
 * 续约作业、取消释放等无用户上下文的系统调用都使用该身份，不透传终端用户令牌。
 */
class ServiceTokenProvider {
    static final String SCOPE = "inventory:reserve";
    private final RestClient auth;
    private final InventoryClientProperties props;
    private final Clock clock;
    private String token;
    private Instant refreshAt = Instant.EPOCH;

    ServiceTokenProvider(RestClient auth, InventoryClientProperties props, Clock clock) {
        this.auth = auth; this.props = props; this.clock = clock;
    }

    /** 返回有效令牌；缓存过期时同步刷新（synchronized：并发调用只申请一次）。 */
    synchronized String token() {
        if (token != null && clock.instant().isBefore(refreshAt)) return token;
        if (props.clientSecret() == null || props.clientSecret().isBlank())
            throw new InventoryAuthException(401, "未配置 manufacturing.workorder.inventory.client-secret（WORKORDER_CLIENT_SECRET）");
        var form = new LinkedMultiValueMap<String, String>();
        form.add("grant_type", "client_credentials");
        form.add("scope", SCOPE);
        try {
            JsonNode body = auth.post().uri("/auth/oauth2/token")
                .headers(h -> h.setBasicAuth(props.clientId(), props.clientSecret()))
                .contentType(MediaType.APPLICATION_FORM_URLENCODED).body(form)
                .retrieve().body(JsonNode.class);
            String t = body == null ? null : body.path("access_token").asText(null);
            if (t == null) throw new InventoryAuthException(401, "令牌端点未返回 access_token");
            long ttl = body.path("expires_in").asLong(300);
            token = t;
            refreshAt = clock.instant().plusSeconds(Math.max(ttl - props.tokenRefreshSkew().toSeconds(), 1));
            return token;
        } catch (RestClientResponseException e) {
            int s = e.getStatusCode().value();
            // 4xx：客户端凭据被拒（配置问题，告警）；5xx：认证服务不可用，按不可用退避
            if (s >= 500) throw new InventoryUnavailableException("认证服务不可用（HTTP " + s + "）", e);
            throw new InventoryAuthException(s, "申请服务令牌被拒绝");
        } catch (RestClientException | IllegalStateException e) {
            throw new InventoryUnavailableException("认证服务不可达：" + e.getMessage(), e);
        }
    }

    /** 丢弃缓存的令牌（库存返回 401 时调用），下次调用重新申请。 */
    synchronized void invalidate() { token = null; refreshAt = Instant.EPOCH; }

    /** 写入 Authorization 头。 */
    void apply(HttpHeaders headers) { headers.setBearerAuth(token()); }
}
