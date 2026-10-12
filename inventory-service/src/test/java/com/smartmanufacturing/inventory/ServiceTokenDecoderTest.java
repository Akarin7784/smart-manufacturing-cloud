package com.smartmanufacturing.inventory;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtValidationException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Date;
import java.util.List;
import java.util.function.Consumer;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 直接验证预留链与 qc-hold 链共用的 {@code serviceDecoder()}（#44）：用本地 JWKS（JDK 内置 HttpServer，不依赖 Docker）
 * 签发真实 RS256 令牌。aud 错误、token_type 缺少或错误都在解码阶段抛 {@link JwtValidationException}，
 * 资源服务器过滤器据此返回 401（MockMvc 的 jwt() 会跳过解码器，所以这里不用 MockMvc）。
 */
class ServiceTokenDecoderTest {
    private static final String ISSUER = "http://auth.test";
    private static HttpServer server;
    private static RSAKey key;
    private static JwtDecoder decoder;

    @BeforeAll static void start() throws Exception {
        key = new RSAKeyGenerator(2048).keyID("test").generate();
        byte[] jwks = new JWKSet(key.toPublicJWK()).toString().getBytes(StandardCharsets.UTF_8);
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/jwks", ex -> {
            ex.getResponseHeaders().add("Content-Type", "application/json");
            ex.sendResponseHeaders(200, jwks.length);
            ex.getResponseBody().write(jwks);
            ex.close();
        });
        server.start();
        String uri = "http://127.0.0.1:" + server.getAddress().getPort() + "/jwks";
        decoder = new InventorySecurityConfiguration(uri, ISSUER, "manufacturing-api", "inventory-service").serviceDecoder();
    }

    @AfterAll static void stop() { server.stop(0); }

    /** 签发令牌：默认是合法的质检服务令牌，由 customizer 改动个别声明。 */
    private static String token(Consumer<JWTClaimsSet.Builder> customizer) throws Exception {
        JWTClaimsSet.Builder claims = new JWTClaimsSet.Builder().issuer(ISSUER).subject("quality-service")
            .audience(List.of("inventory-service")).expirationTime(new Date(System.currentTimeMillis() + 300_000))
            .issueTime(new Date()).claim("client_id", "quality-service").claim("scope", "inventory:qc-hold").claim("token_type", "service");
        customizer.accept(claims);
        SignedJWT jwt = new SignedJWT(new JWSHeader.Builder(JWSAlgorithm.RS256).keyID(key.getKeyID()).build(), claims.build());
        jwt.sign(new RSASSASigner(key));
        return jwt.serialize();
    }

    @Test void validServiceTokenIsAccepted() throws Exception {
        assertThat(decoder.decode(token(c -> {})).getClaimAsString("client_id")).isEqualTo("quality-service");
    }

    @Test void wrongAudienceIsRejected() throws Exception {
        assertThatThrownBy(() -> decoder.decode(token(c -> c.audience(List.of("manufacturing-api")))))
            .isInstanceOf(JwtValidationException.class).hasMessageContaining("受众");
    }

    @Test void missingTokenTypeIsRejected() throws Exception {
        assertThatThrownBy(() -> decoder.decode(token(c -> c.claim("token_type", null))))
            .isInstanceOf(JwtValidationException.class).hasMessageContaining("token_type");
    }

    @Test void wrongTokenTypeIsRejected() throws Exception {
        assertThatThrownBy(() -> decoder.decode(token(c -> c.claim("token_type", "user"))))
            .isInstanceOf(JwtValidationException.class).hasMessageContaining("token_type");
    }
}
