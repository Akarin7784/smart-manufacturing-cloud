package com.smartmanufacturing.workorder;

import feign.RequestTemplate;
import feign.Target;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

/** #74：质量服务调用使用服务令牌替换用户令牌；全局拦截器对 quality-service 不透传用户令牌，对其他客户端照常透传。 */
class QualityServiceTokenTest {
    @AfterEach void clear() { SecurityContextHolder.clearContext(); }

    private static RequestTemplate template(String target) {
        RequestTemplate t = new RequestTemplate();
        t.feignTarget(new Target.HardCodedTarget<>(Object.class, target, "http://" + target));
        return t;
    }

    @Test void serviceTokenReplacesAuthorization() {
        ServiceTokenProvider provider = mock(ServiceTokenProvider.class);
        when(provider.token()).thenReturn("svc-token");
        QualityServiceToken token = new QualityServiceToken(provider);
        RequestTemplate t = template("quality-service");
        t.header("Authorization", "Bearer user-token");
        new QualityFeignConfiguration().qualityServiceTokenInterceptor(token).apply(t);
        assertThat(t.headers().get("Authorization")).containsExactly("Bearer svc-token");
        token.invalidate();
        verify(provider).invalidate();
    }

    @Test void globalInterceptorSkipsQualityOnly() {
        Jwt jwt = Jwt.withTokenValue("user-token").header("alg", "none").subject("u").issuedAt(Instant.now()).expiresAt(Instant.now().plusSeconds(60)).build();
        SecurityContextHolder.getContext().setAuthentication(new JwtAuthenticationToken(jwt));
        var relay = new FeignConfiguration().requestIdInterceptor();
        RequestTemplate quality = template("quality-service");
        relay.apply(quality);
        assertThat(quality.headers()).doesNotContainKey("Authorization");
        RequestTemplate masterdata = template("masterdata-service");
        relay.apply(masterdata);
        assertThat(masterdata.headers().get("Authorization")).containsExactly("Bearer user-token");
    }
}
