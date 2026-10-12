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
            .andRespond(withStatus(HttpStatus.UNAUTHORIZED).contentType(MediaType.parseMediaType(PROBLEM)).body("{\"type\":\"authentication-required\"}"));
        inventoryServer.expect(requestTo("http://inventory/inventory/qc-hold/receipts")).andExpect(header(HttpHeaders.AUTHORIZATION, "Bearer new"))
            .andRespond(withStatus(HttpStatus.CREATED).contentType(MediaType.APPLICATION_JSON).body(txn(9)));
        assertThat(client.call(call(Action.HOLD, null, null))).isEqualTo(new Booked(9L, false));
        authServer.verify();
        inventoryServer.verify();
    }

    @Test void repeatedUnauthorizedIsRetryable() {
        expectToken("a");
        expectToken("b");
        inventoryServer.expect(requestTo("http://inventory/inventory/qc-hold/receipts")).andRespond(withStatus(HttpStatus.UNAUTHORIZED));
        inventoryServer.expect(requestTo("http://inventory/inventory/qc-hold/receipts")).andRespond(withStatus(HttpStatus.UNAUTHORIZED));
        assertThat(client.call(call(Action.HOLD, null, null))).isInstanceOf(Retryable.class);
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
        assertThat(classify(503, null)).isInstanceOf(Retryable.class);
        assertThat(classify(500, null)).isInstanceOf(Retryable.class);
        assertThat(classify(429, null)).isInstanceOf(Retryable.class);
        assertThat(classify(403, "access-denied")).isInstanceOf(Retryable.class);
        assertThat(classify(400, "validation-error")).isEqualTo(new Rejected("HTTP 400 validation-error：明细", true));
        assertThat(classify(422, "uom-mismatch")).isInstanceOfSatisfying(Rejected.class, r -> assertThat(r.certain()).isTrue());
        assertThat(classify(409, "insufficient-stock")).isInstanceOfSatisfying(Rejected.class, r -> assertThat(r.certain()).isTrue());
        // 幂等键冲突不能证明未记账（首次请求可能已记账）。
        assertThat(classify(422, "idempotency-key-conflict")).isInstanceOfSatisfying(Rejected.class, r -> assertThat(r.certain()).isFalse());
        assertThat(classify(409, "other")).isInstanceOfSatisfying(Rejected.class, r -> assertThat(r.certain()).isFalse());
        assertThat(client.classify(new HttpInventoryQcHoldClient.Raw(201, "not json", null))).isEqualTo(new Booked(null, false));
    }

    @Test void lastErrorIsTruncatedTo512() {
        assertThat(HttpInventoryQcHoldClient.truncate("x".repeat(600))).hasSize(512);
    }

    private InventoryQcHoldClient.Outcome classify(int status, String type) {
        String body = type == null ? "" : "{\"type\":\"" + type + "\",\"detail\":\"明细\"}";
        return client.classify(new HttpInventoryQcHoldClient.Raw(status, body, null));
    }
}
