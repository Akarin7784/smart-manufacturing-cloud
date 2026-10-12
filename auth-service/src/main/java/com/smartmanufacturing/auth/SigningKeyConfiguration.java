package com.smartmanufacturing.auth;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.jwk.*;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import java.nio.channels.FileChannel;
import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermissions;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.*;
import org.springframework.security.oauth2.jwt.*;

/** 认证服务独占私钥；持久化随机 RSA 密钥，重启后已有令牌仍可验签。 */
@Configuration
public class SigningKeyConfiguration {
    /** 密钥文件路径必须位于被忽略的运行目录或受控数据卷，绝不提交到仓库。 */
    @Bean RSAKey signingKey(@Value("${auth.private-key-path:.runtime/auth/signing-key.json}") String location) throws Exception {
        Path path = Path.of(location).toAbsolutePath();
        Files.createDirectories(path.getParent());
        // 通过文件锁序列化同一目录内的初始化，避免多个进程同时生成不同签名密钥。
        try (FileChannel channel = FileChannel.open(path.resolveSibling("signing-key.lock"),
                StandardOpenOption.CREATE, StandardOpenOption.WRITE); var lock = channel.lock()) {
            if (!Files.exists(path)) {
                RSAKey generated = new RSAKeyGenerator(2048).keyID(java.util.UUID.randomUUID().toString())
                    .algorithm(JWSAlgorithm.RS256).generate();
                if (Files.getFileStore(path.getParent()).supportsFileAttributeView("posix")) {
                    Files.createFile(path, PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")));
                } else {
                    Files.createFile(path);
                }
                Files.writeString(path, generated.toJSONString(), StandardOpenOption.TRUNCATE_EXISTING);
            }
            RSAKey key = RSAKey.parse(Files.readString(path));
            if (!key.isPrivate() || key.size() < 2048) throw new IllegalStateException("RSA 签名密钥必须包含至少 2048 位私钥");
            return key;
        }
    }

    /** 使用私钥签发 RS256 令牌；资源服务仅通过 JWKS 获取对应公钥。 */
    @Bean JwtEncoder jwtEncoder(RSAKey key) {
        var source = new com.nimbusds.jose.jwk.source.ImmutableJWKSet<com.nimbusds.jose.proc.SecurityContext>(new JWKSet(key));
        return new NimbusJwtEncoder(source);
    }
}
