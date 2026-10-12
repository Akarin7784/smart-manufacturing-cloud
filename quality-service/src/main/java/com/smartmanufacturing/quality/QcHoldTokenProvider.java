package com.smartmanufacturing.quality;

import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.http.MediaType;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;
import java.time.Clock;
import java.time.Instant;

/**
 * 质检服务身份令牌（OAuth2 client_credentials，scope=inventory:qc-hold）：向 auth-service 的 /auth/oauth2/token 申请并缓存，
 * 剩余有效期小于 tokenRefreshSkew（默认 60s）时才刷新，不得每次调用都重新申请；库存返回 401 时 {@link #invalidate()} 后重取一次。
 * 写法与工单服务的 ServiceTokenProvider 一致；令牌 aud=inventory-service、token_type=service 由认证服务按客户端注册签发。
 */
class QcHoldTokenProvider {
    private final RestClient auth;
    private final WritebackProperties.Client props;
    private final Clock clock;
    private String token;
    private Instant refreshAt = Instant.EPOCH;

    QcHoldTokenProvider(RestClient auth, WritebackProperties.Client props, Clock clock) {
        this.auth = auth; this.props = props; this.clock = clock;
    }

    /** 申请令牌失败（凭据被拒、认证服务不可用等），调用方按可重试处理。 */
    static final class TokenException extends RuntimeException {
        TokenException(String message, Throwable cause) { super(message, cause); }
    }

    /** 返回有效令牌；缓存过期时同步刷新（synchronized：并发调用只申请一次）。 */
    synchronized String token() {
        if (token != null && clock.instant().isBefore(refreshAt)) return token;
        if (!props.configured()) throw new TokenException("未配置 mes.quality.writeback.client.client-secret（QUALITY_CLIENT_SECRET）", null);
        var form = new LinkedMultiValueMap<String, String>();
        form.add("grant_type", "client_credentials");
        form.add("scope", props.scope());
        try {
            JsonNode body = auth.post().uri("/auth/oauth2/token")
                .headers(h -> h.setBasicAuth(props.clientId(), props.clientSecret()))
                .contentType(MediaType.APPLICATION_FORM_URLENCODED).body(form)
                .retrieve().body(JsonNode.class);
            String t = body == null ? null : body.path("access_token").asText(null);
            if (t == null) throw new TokenException("令牌端点未返回 access_token", null);
            long ttl = body.path("expires_in").asLong(300);
            token = t;
            refreshAt = clock.instant().plusSeconds(Math.max(ttl - props.tokenRefreshSkew().toSeconds(), 1));
            return token;
        } catch (RestClientResponseException e) {
            // 4xx 为凭据或 scope 配置问题，5xx 为认证服务不可用；两者都不能证明库存是否记账，统一按可重试处理。
            throw new TokenException("申请服务令牌失败（HTTP " + e.getStatusCode().value() + "）", e);
        } catch (RestClientException e) {
            throw new TokenException("认证服务不可达：" + e.getMessage(), e);
        }
    }

    /** 丢弃缓存的令牌（库存返回 401 时调用），下次调用重新申请。 */
    synchronized void invalidate() { token = null; refreshAt = Instant.EPOCH; }
}
