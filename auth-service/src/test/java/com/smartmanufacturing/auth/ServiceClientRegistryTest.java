package com.smartmanufacturing.auth;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import static org.junit.jupiter.api.Assertions.*;

/** 保护客户端注册表的启动校验：cloud 环境缺散列快速失败、禁止明文密钥、local 缺散列仅禁用。 */
class ServiceClientRegistryTest {
    private final BCryptPasswordEncoder encoder = new BCryptPasswordEncoder(ServiceClientRegistry.EXPECTED_COST);

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

    /** 已注册散列 cost 与约定不一致时拒绝启动，保证未知客户端的假散列比对耗时与真实客户端一致。 */
    @Test void costMismatchRejected() {
        String cost4 = new BCryptPasswordEncoder(4).encode("s3cret");
        assertThrows(IllegalStateException.class, () -> new ServiceClientRegistry(props(cost4), encoder, false));
    }

    /** #74：scope-audiences 只能引用已授权的 scope，受众不能为空。 */
    @Test void scopeAudiencesValidated() {
        ServiceClientProperties p = props(null);
        p.getClients().get(0).setScopeAudiences(java.util.Map.of("quality:read", "quality-service"));
        assertThrows(IllegalStateException.class, () -> new ServiceClientRegistry(p, encoder, false));
        ServiceClientProperties blank = props(null);
        blank.getClients().get(0).setScopes(List.of("inventory:reserve", "quality:read"));
        blank.getClients().get(0).setScopeAudiences(java.util.Map.of("quality:read", " "));
        assertThrows(IllegalStateException.class, () -> new ServiceClientRegistry(blank, encoder, false));
        ServiceClientProperties ok = props(null);
        ok.getClients().get(0).setScopes(List.of("inventory:reserve", "quality:read"));
        ok.getClients().get(0).setScopeAudiences(java.util.Map.of("quality:read", "quality-service"));
        assertDoesNotThrow(() -> new ServiceClientRegistry(ok, encoder, false));
        assertEquals("quality-service", ok.getClients().get(0).audienceOf("quality:read"));
        assertEquals("inventory-service", ok.getClients().get(0).audienceOf("inventory:reserve"));
    }
}
