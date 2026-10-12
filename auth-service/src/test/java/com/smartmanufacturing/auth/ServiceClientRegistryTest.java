package com.smartmanufacturing.auth;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import static org.junit.jupiter.api.Assertions.*;

/** 保护客户端注册表的启动校验：cloud 环境缺散列快速失败、禁止明文密钥、local 缺散列仅禁用。 */
class ServiceClientRegistryTest {
    private final BCryptPasswordEncoder encoder = new BCryptPasswordEncoder(4);

    private static ServiceClientProperties props(String hash) {
        ServiceClientProperties.Client client = new ServiceClientProperties.Client();
        client.setClientId("workorder-service");
        client.setSecretHash(hash);
        client.setScopes(List.of("inventory:reserve"));
        client.setAudience("inventory-service");
        ServiceClientProperties properties = new ServiceClientProperties();
        properties.setClients(List.of(client));
        return properties;
    }

    /** require-secrets=true（cloud）时缺少散列必须启动失败。 */
    @Test void missingHashFailsFastWhenRequired() {
        assertThrows(IllegalStateException.class, () -> new ServiceClientRegistry(props(""), encoder, true));
    }

    /** local/test 缺少散列时客户端被禁用，任何密钥都无法认证。 */
    @Test void missingHashDisablesClientLocally() {
        ServiceClientRegistry registry = new ServiceClientRegistry(props(null), encoder, false);
        assertTrue(registry.authenticate("workorder-service", "").isEmpty());
    }

    /** 配置了明文而不是 BCrypt 散列时拒绝启动，防止认证服务持有明文密钥。 */
    @Test void plaintextSecretRejected() {
        assertThrows(IllegalStateException.class, () -> new ServiceClientRegistry(props("plain-text-secret"), encoder, false));
    }

    /** htpasswd 生成的 $2y$ 散列也必须可用（init-env 默认使用 htpasswd）。 */
    @Test void acceptsHtpasswdStyleHash() {
        String hash = encoder.encode("s3cret").replaceFirst("^\\$2a\\$", "\\$2y\\$");
        ServiceClientRegistry registry = new ServiceClientRegistry(props(hash), encoder, true);
        assertTrue(registry.authenticate("workorder-service", "s3cret").isPresent());
        assertTrue(registry.authenticate("workorder-service", "wrong").isEmpty());
    }
}
