package com.smartmanufacturing.quality;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.smartmanufacturing.quality.InventoryQcHoldClient.Action;
import com.smartmanufacturing.quality.InventoryQcHoldClient.Booked;
import com.smartmanufacturing.quality.InventoryQcHoldClient.Call;
import com.smartmanufacturing.quality.InventoryQcHoldClient.Rejected;
import com.smartmanufacturing.quality.InventoryQcHoldClient.Retryable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;
import java.time.Duration;
import java.time.Instant;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.ExpectedCount.once;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.*;
import static org.springframework.test.web.client.response.MockRestResponseCreators.*;

/**
 * 库存 QC-HOLD HTTP 客户端（不启动 Spring）：请求路径与请求体按契约组装（不带库位、VOID 不带 dispositionId）、
 * 令牌缓存与临近过期刷新、401 作废令牌重取一次、响应分类（可重试 / 明确拒绝 / 不确定）。时间由 {@link MutableClock} 控制。
 */
class HttpInventoryQcHoldClientTest {
    private static final String PROBLEM = "application/problem+json";
    private final ObjectMapper mapper = new ObjectMapper();
    private final MutableClock clock = new MutableClock();
    private MockRestServiceServer authServer;
    private MockRestServiceServer inventoryServer;
    private HttpInventoryQcHoldClient client;

    @BeforeEach void setUp() {
        clock.pin(Instant.parse("2026-10-12T08:00:00Z"));
        WritebackProperties.Client props = new WritebackProperties.Client(null, "secret", null, "http://inventory", "http://auth",
            null, null, null, null, null);
        RestClient.Builder auth = RestClient.builder().baseUrl("http://auth");
        RestClient.Builder inv = RestClient.builder().baseUrl("http://inventory");
        authServer = MockRestServiceServer.bindTo(auth).build();
        inventoryServer = MockRestServiceServer.bindTo(inv).build();
        client = new HttpInventoryQcHoldClient(inv.build(), new QcHoldTokenProvider(auth.build(), props, clock), mapper, props);
    }

    private void expectToken(String token) {
        authServer.expect(once(), requestTo("http://auth/auth/oauth2/token")).andExpect(method(HttpMethod.POST))
            .andExpect(header(HttpHeaders.AUTHORIZATION, "Basic cXVhbGl0eS1zZXJ2aWNlOnNlY3JldA=="))
            .andExpect(content().string(org.hamcrest.Matchers.containsString("scope=inventory%3Aqc-hold")))
            .andRespond(withSuccess("{\"access_token\":\"" + token + "\",\"expires_in\":300}", MediaType.APPLICATION_JSON));
    }

    private static Call call(Action action, String reason, Long disp) {
        String key = switch (action) {
            case HOLD -> "qc:NCR-20261012-0001:hold";
            case VOID -> "qc:NCR-20261012-0001:void";
            case SCRAP -> "qc:NCR-20261012-0001:disp:" + disp + ":scrap";
            case RELEASE -> "qc:NCR-20261012-0001:disp:" + disp + ":release";
        };
        return new Call(action, key, "NCR-20261012-0001", "FG-GR60", "件", "LOT-1", "2.5", reason, disp);
    }

    private static String txn(long id) { return "{\"txnId\":" + id + "}"; }

    @Test void requestBodiesFollowContractAndTokenIsCached() {
        expectToken("t1");
        inventoryServer.expect(requestTo("http://inventory/inventory/qc-hold/receipts"))
            .andExpect(header(HttpHeaders.AUTHORIZATION, "Bearer t1"))
            .andExpect(header("Idempotency-Key", "qc:NCR-20261012-0001:hold"))
            .andExpect(content().json("{\"ncrNo\":\"NCR-20261012-0001\",\"materialCode\":\"FG-GR60\",\"uom\":\"件\",\"lotNo\":\"LOT-1\",\"qty\":\"2.5\"}", true))
            .andRespond(withStatus(HttpStatus.CREATED).contentType(MediaType.APPLICATION_JSON).body(txn(1)));
        inventoryServer.expect(requestTo("http://inventory/inventory/qc-hold/scraps"))
            .andExpect(header(HttpHeaders.AUTHORIZATION, "Bearer t1"))
            .andExpect(content().json("{\"ncrNo\":\"NCR-20261012-0001\",\"materialCode\":\"FG-GR60\",\"uom\":\"件\",\"lotNo\":\"LOT-1\",\"qty\":\"2.5\",\"dispositionId\":1}", true))
            .andRespond(withStatus(HttpStatus.CREATED).contentType(MediaType.APPLICATION_JSON).body(txn(2)));
        inventoryServer.expect(requestTo("http://inventory/inventory/qc-hold/releases"))
            .andExpect(content().json("{\"ncrNo\":\"NCR-20261012-0001\",\"materialCode\":\"FG-GR60\",\"uom\":\"件\",\"lotNo\":\"LOT-1\",\"qty\":\"2.5\",\"reason\":\"REWORK\",\"dispositionId\":2}", true))
            .andRespond(withStatus(HttpStatus.CREATED).contentType(MediaType.APPLICATION_JSON).body(txn(3)));
        inventoryServer.expect(requestTo("http://inventory/inventory/qc-hold/releases"))
            .andExpect(content().json("{\"ncrNo\":\"NCR-20261012-0001\",\"materialCode\":\"FG-GR60\",\"uom\":\"件\",\"lotNo\":\"LOT-1\",\"qty\":\"2.5\",\"reason\":\"NCR_VOIDED\"}", true))
            .andRespond(withStatus(HttpStatus.CREATED).contentType(MediaType.APPLICATION_JSON).header("Idempotent-Replayed", "true").body(txn(4)));

        assertThat(client.call(call(Action.HOLD, null, null))).isEqualTo(new Booked(1L, false));
        assertThat(client.call(call(Action.SCRAP, null, 1L))).isEqualTo(new Booked(2L, false));
        assertThat(client.call(call(Action.RELEASE, "REWORK", 2L))).isEqualTo(new Booked(3L, false));
        assertThat(client.call(call(Action.VOID, "NCR_VOIDED", null))).isEqualTo(new Booked(4L, true));
        // 四次调用只申请一次令牌。
        authServer.verify();
        inventoryServer.verify();
    }

    @Test void tokenRefreshedWhenLessThan60sRemain() {
        expectToken("t1");
        expectToken("t2");
        inventoryServer.expect(requestTo("http://inventory/inventory/qc-hold/receipts")).andExpect(header(HttpHeaders.AUTHORIZATION, "Bearer t1"))
            .andRespond(withStatus(HttpStatus.CREATED).contentType(MediaType.APPLICATION_JSON).body(txn(1)));
        inventoryServer.expect(requestTo("http://inventory/inventory/qc-hold/receipts")).andExpect(header(HttpHeaders.AUTHORIZATION, "Bearer t1"))
            .andRespond(withStatus(HttpStatus.CREATED).contentType(MediaType.APPLICATION_JSON).body(txn(1)));
        inventoryServer.expect(requestTo("http://inventory/inventory/qc-hold/receipts")).andExpect(header(HttpHeaders.AUTHORIZATION, "Bearer t2"))
            .andRespond(withStatus(HttpStatus.CREATED).contentType(MediaType.APPLICATION_JSON).body(txn(1)));
        client.call(call(Action.HOLD, null, null));
        clock.advance(Duration.ofSeconds(239));
        client.call(call(Action.HOLD, null, null));
        clock.advance(Duration.ofSeconds(1));
        client.call(call(Action.HOLD, null, null));
        authServer.verify();
        inventoryServer.verify();
    }

    @Test void unauthorizedInvalidatesTokenAndRetriesOnce() {
        expectToken("old");
        expectToken("new");
        inventoryServer.expect(requestTo("http://inventory/inventory/qc-hold/receipts")).andExpect(header(HttpHeaders.AUTHORIZATION, "Bearer old"))
            .andExpect(header("Idempotency-Key", "qc:NCR-20261012-0001:hold"))
            .andRespond(withStatus(HttpStatus.UNAUTHORIZED).contentType(MediaType.parseMediaType(PROBLEM)).body("{\"type\":\"authentication-required\"}"));
        // 强制刷新令牌后用同一幂等键重试一次；成功即已记账，不带鉴权失败信号（不计 auth_failure）。
        inventoryServer.expect(requestTo("http://inventory/inventory/qc-hold/receipts")).andExpect(header(HttpHeaders.AUTHORIZATION, "Bearer new"))
            .andExpect(header("Idempotency-Key", "qc:NCR-20261012-0001:hold"))
            .andRespond(withStatus(HttpStatus.CREATED).contentType(MediaType.APPLICATION_JSON).body(txn(9)));
        assertThat(client.call(call(Action.HOLD, null, null))).isEqualTo(new Booked(9L, false, false));
        authServer.verify();
        inventoryServer.verify();
    }

    /** 刷新令牌后仍 401：FAILED（确定未记账），带鉴权失败信号；只重试一次（共两次调用、两次申请令牌）。 */
    @Test void unauthorizedAfterRefreshFails() {
        expectToken("a");
        expectToken("b");
        inventoryServer.expect(requestTo("http://inventory/inventory/qc-hold/receipts")).andExpect(header(HttpHeaders.AUTHORIZATION, "Bearer a"))
            .andExpect(header("Idempotency-Key", "qc:NCR-20261012-0001:hold")).andRespond(withStatus(HttpStatus.UNAUTHORIZED));
        inventoryServer.expect(requestTo("http://inventory/inventory/qc-hold/receipts")).andExpect(header(HttpHeaders.AUTHORIZATION, "Bearer b"))
            .andExpect(header("Idempotency-Key", "qc:NCR-20261012-0001:hold")).andRespond(withStatus(HttpStatus.UNAUTHORIZED));
        assertThat(client.call(call(Action.HOLD, null, null))).isInstanceOfSatisfying(Rejected.class, r -> {
            assertThat(r.certain()).isTrue();
            assertThat(r.signal()).isEqualTo(InventoryQcHoldClient.Signal.AUTH);
        });
        authServer.verify();
        inventoryServer.verify();
    }

    /** 令牌端点 4xx（凭据被拒）：FAILED，计 operation=token。 */
    @Test void tokenEndpointRejectionFails() {
        authServer.expect(requestTo("http://auth/auth/oauth2/token")).andRespond(withStatus(HttpStatus.UNAUTHORIZED));
        assertThat(client.call(call(Action.HOLD, null, null))).isInstanceOfSatisfying(Rejected.class,
            r -> assertThat(r.signal()).isEqualTo(InventoryQcHoldClient.Signal.TOKEN_AUTH));
    }

    @Test void tokenEndpointFailureIsRetryable() {
        authServer.expect(requestTo("http://auth/auth/oauth2/token")).andRespond(withServerError());
        assertThat(client.call(call(Action.HOLD, null, null))).isInstanceOfSatisfying(Retryable.class,
            r -> assertThat(r.error()).contains("申请服务令牌失败"));
    }

    @Test void networkErrorIsRetryable() {
        expectToken("t");
        inventoryServer.expect(requestTo("http://inventory/inventory/qc-hold/receipts"))
            .andRespond(request -> { throw new java.net.SocketTimeoutException("Read timed out"); });
        assertThat(client.call(call(Action.HOLD, null, null))).isInstanceOfSatisfying(Retryable.class,
            r -> assertThat(r.error()).contains("Read timed out"));
    }

    @Test void responsesAreClassified() {
        var S = InventoryQcHoldClient.Signal.class;
        var R = InventoryQcHoldClient.RetryReason.class;
        assertThat(((Retryable) classify(503, null)).reason()).isEqualTo(InventoryQcHoldClient.RetryReason.SERVER);
        assertThat(((Retryable) classify(500, null)).reason()).isEqualTo(InventoryQcHoldClient.RetryReason.SERVER);
        assertThat(((Retryable) classify(429, null)).reason()).isEqualTo(InventoryQcHoldClient.RetryReason.TOO_MANY);
        assertThat(((Retryable) classify(408, null)).reason()).isEqualTo(InventoryQcHoldClient.RetryReason.NETWORK);
        // 约定的三种 404：确定未记账，FAILED 不重试，不计任何信号。
        for (String t : java.util.List.of("location-not-found", "material-not-found", "lot-not-found"))
            assertThat(classify(404, t)).isEqualTo(new Rejected("HTTP 404 " + t + "：明细", true, InventoryQcHoldClient.Signal.NONE));
        // 其他 404（无 type / about:blank / gateway-not-found / 未定义 type）：按 not_found_unknown 重试，次数由执行器限制。
        for (String t : java.util.Arrays.asList(null, "about:blank", "gateway-not-found", "ncr-not-found"))
            assertThat(((Retryable) classify(404, t)).reason()).isEqualTo(InventoryQcHoldClient.RetryReason.NOT_FOUND_UNKNOWN);
        assertThat(R).isNotNull();
        assertThat(classify(403, "access-denied")).isEqualTo(new Rejected("HTTP 403 access-denied：明细", true, InventoryQcHoldClient.Signal.AUTH));
        assertThat(classify(400, "validation-error")).isEqualTo(new Rejected("HTTP 400 validation-error：明细", true, InventoryQcHoldClient.Signal.CONTRACT));
        assertThat(classify(422, "uom-mismatch")).isEqualTo(new Rejected("HTTP 422 uom-mismatch：明细", true, InventoryQcHoldClient.Signal.NONE));
        assertThat(classify(409, "insufficient-stock")).isEqualTo(new Rejected("HTTP 409 insufficient-stock：明细", true, InventoryQcHoldClient.Signal.NONE));
        // 幂等键冲突不能证明未记账（首次请求可能已记账），计契约不一致。
        assertThat(classify(422, "idempotency-key-conflict")).isEqualTo(new Rejected("HTTP 422 idempotency-key-conflict：明细", false, InventoryQcHoldClient.Signal.CONTRACT));
        assertThat(classify(409, "other")).isEqualTo(new Rejected("HTTP 409 other：明细", false, InventoryQcHoldClient.Signal.CONTRACT));
        // 2xx 缺 txnId：仍按已记账，标记契约不一致。
        assertThat(client.classify(new HttpInventoryQcHoldClient.Raw(201, "not json", null))).isEqualTo(new Booked(null, false, true));
        assertThat(S).isNotNull();
    }

    @Test void lastErrorIsTruncatedTo512() {
        assertThat(HttpInventoryQcHoldClient.truncate("x".repeat(600))).hasSize(512);
    }

    private InventoryQcHoldClient.Outcome classify(int status, String type) {
        String body = type == null ? "" : "{\"type\":\"" + type + "\",\"detail\":\"明细\"}";
        return client.classify(new HttpInventoryQcHoldClient.Raw(status, body, null));
    }

    /** ready() 按是否配置密钥判断；未配置时令牌提供者直接明确拒绝（TOKEN_AUTH），不访问认证服务。 */
    @Test void readyFollowsSecretAndUnconfiguredTokenIsRejected() {
        assertThat(client.ready()).isTrue();
        WritebackProperties.Client none = new WritebackProperties.Client(null, null, null, "http://inventory", "http://auth",
            null, null, null, null, null);
        RestClient.Builder auth = RestClient.builder().baseUrl("http://auth");
        RestClient.Builder inv = RestClient.builder().baseUrl("http://inventory");
        MockRestServiceServer a = MockRestServiceServer.bindTo(auth).build();
        var unconfigured = new HttpInventoryQcHoldClient(inv.build(), new QcHoldTokenProvider(auth.build(), none, clock), mapper, none);
        assertThat(unconfigured.ready()).isFalse();
        assertThat(unconfigured.call(call(Action.HOLD, null, null)))
            .isInstanceOfSatisfying(Rejected.class, r -> {
                assertThat(r.certain()).isTrue();
                assertThat(r.signal()).isEqualTo(InventoryQcHoldClient.Signal.TOKEN_AUTH);
            });
        a.verify();
    }

    /** 认证服务不可达（连接失败）：令牌异常按 NETWORK 退避重试，未调用库存。 */
    @Test void unreachableAuthServiceIsRetryableNetwork() {
        authServer.expect(once(), requestTo("http://auth/auth/oauth2/token"))
            .andRespond(withException(new java.io.IOException("connection refused")));
        assertThat(client.call(call(Action.HOLD, null, null)))
            .isInstanceOfSatisfying(Retryable.class, r -> assertThat(r.reason()).isEqualTo(InventoryQcHoldClient.RetryReason.NETWORK));
        authServer.verify();
        inventoryServer.verify();
    }

    /** 错误响应体不是合法 JSON：取不到 type，按状态码分类（400 → 明确拒绝并计契约不一致）。 */
    @Test void nonJsonProblemBodyIsClassifiedByStatus() {
        expectToken("t1");
        inventoryServer.expect(requestTo("http://inventory/inventory/qc-hold/receipts"))
            .andRespond(withStatus(HttpStatus.BAD_REQUEST).contentType(MediaType.parseMediaType(PROBLEM)).body("<html>bad</html>"));
        assertThat(client.call(call(Action.HOLD, null, null)))
            .isInstanceOfSatisfying(Rejected.class, r -> assertThat(r.signal()).isEqualTo(InventoryQcHoldClient.Signal.CONTRACT));
    }
}
