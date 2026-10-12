package com.smartmanufacturing.auth;

import java.util.*;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

/**
 * 校验服务客户端凭据。启动时检查配置：cloud 环境（require-secrets=true）缺少散列直接启动失败，
 * 避免演示或生产环境静默关闭服务身份；local/test 环境缺少散列时仅禁用该客户端。
 */
@Component
public class ServiceClientRegistry {
    private static final Logger log = LoggerFactory.getLogger(ServiceClientRegistry.class);
    /** BCrypt 散列格式；拒绝明文或其他算法，确保认证服务从不持有可直接使用的密钥。 */
    private static final Pattern BCRYPT = Pattern.compile("^\\$2[aby]?\\$\\d{2}\\$[./A-Za-z0-9]{53}$");
    /** scope 字符集按 RFC 6749 3.3 的 scope-token 约束，同时排除空格以便按空格拼接。 */
    private static final Pattern SCOPE = Pattern.compile("^[\\x21\\x23-\\x5B\\x5D-\\x7E]+$");

    private final Map<String, ServiceClientProperties.Client> clients = new HashMap<>();
    private final PasswordEncoder passwords;
    /** 未知客户端时用于比对的假散列，使未知客户端与密钥错误的耗时接近，避免枚举客户端。 */
    private final String dummyHash;

    public ServiceClientRegistry(ServiceClientProperties properties, PasswordEncoder passwords,
            @Value("${manufacturing.auth.require-secrets:false}") boolean requireSecrets) {
        this.passwords = passwords;
        this.dummyHash = passwords.encode(UUID.randomUUID().toString());
        for (ServiceClientProperties.Client client : properties.getClients()) {
            String id = client.getClientId();
            if (id == null || id.isBlank()) throw new IllegalStateException("服务客户端缺少 client-id");
            if (client.getAudience() == null || client.getAudience().isBlank())
                throw new IllegalStateException("服务客户端 " + id + " 缺少 audience");
            if (client.getScopes().isEmpty() || !client.getScopes().stream().allMatch(s -> SCOPE.matcher(s).matches()))
                throw new IllegalStateException("服务客户端 " + id + " 的 scopes 为空或含非法字符");
            String hash = client.getSecretHash();
            if (hash == null || hash.isBlank()) {
                if (requireSecrets) throw new IllegalStateException("服务客户端 " + id + " 缺少密钥散列，请运行 scripts/init-env.sh 生成");
                log.warn("服务客户端 {} 未配置密钥散列，已禁用", id);
                continue;
            }
            if (!BCRYPT.matcher(hash).matches())
                throw new IllegalStateException("服务客户端 " + id + " 的密钥必须是 BCrypt 散列，禁止配置明文");
            if (clients.putIfAbsent(id, client) != null) throw new IllegalStateException("服务客户端重复注册：" + id);
        }
    }

    /**
     * 校验客户端凭据，成功返回客户端配置。未知客户端同样执行一次 BCrypt 比对，
     * 且不区分“客户端不存在”和“密钥错误”，避免通过响应或耗时探测已注册的客户端。
     */
    public Optional<ServiceClientProperties.Client> authenticate(String clientId, String secret) {
        if (clientId == null || secret == null) return Optional.empty();
        ServiceClientProperties.Client client = clients.get(clientId);
        // BCrypt 本身按常量时间比较散列，未知客户端使用假散列保持耗时一致。
        boolean matches = passwords.matches(secret, client == null ? dummyHash : client.getSecretHash());
        return client != null && matches ? Optional.of(client) : Optional.empty();
    }
}
