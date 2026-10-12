package com.smartmanufacturing.auth;

import java.util.ArrayList;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 服务间调用（OAuth2 client_credentials）的客户端注册表，绑定 {@code manufacturing.auth.clients}。
 * <p>认证服务只保存客户端密钥的 BCrypt 散列，明文密钥只交给调用方服务；散列由 init-env 生成并经环境变量注入，
 * 不写入仓库或 Nacos 明文配置。</p>
 */
@ConfigurationProperties(prefix = "manufacturing.auth")
public class ServiceClientProperties {
    /** 已注册的服务客户端列表。 */
    private List<Client> clients = new ArrayList<>();

    public List<Client> getClients() { return clients; }
    public void setClients(List<Client> clients) { this.clients = clients; }

    /** 单个服务客户端：身份、密钥散列、允许申请的权限范围和令牌受众。 */
    public static class Client {
        /** 客户端标识，同时作为令牌的 sub 与 client_id。 */
        private String clientId;
        /** 客户端密钥的 BCrypt 散列（$2a/$2b/$2y）；为空表示该客户端未启用。 */
        private String secretHash;
        /** 允许申请的 scope；请求未带 scope 时签发全部允许值。 */
        private List<String> scopes = new ArrayList<>();
        /** 令牌受众，即唯一允许接受该令牌的资源服务（如 inventory-service）。 */
        private String audience;

        public String getClientId() { return clientId; }
        public void setClientId(String clientId) { this.clientId = clientId; }
        public String getSecretHash() { return secretHash; }
        public void setSecretHash(String secretHash) { this.secretHash = secretHash; }
        public List<String> getScopes() { return scopes; }
        public void setScopes(List<String> scopes) { this.scopes = scopes; }
        public String getAudience() { return audience; }
        public void setAudience(String audience) { this.audience = audience; }
    }
}
