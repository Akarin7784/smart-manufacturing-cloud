package com.smartmanufacturing.quality;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

/**
 * 从已验证的令牌解析调用方身份，作为幂等记录的作用域与流水操作人。
 * 服务身份令牌（客户端凭证）取 {@code client_id} 声明；用户令牌没有该声明，取 subject（用户名）。
 * 只读已验签的令牌声明，不接受任何请求头声明的身份。
 */
public final class CallerIdentity {
    private CallerIdentity() {}

    public static String of(Authentication authentication) {
        if (authentication instanceof JwtAuthenticationToken jwt) {
            String clientId = jwt.getToken().getClaimAsString("client_id");
            if (clientId != null && !clientId.isBlank()) return clientId;
            return jwt.getToken().getSubject();
        }
        // 非 JWT 认证只出现在测试替身中，退回到认证名称。
        return authentication.getName();
    }
}
