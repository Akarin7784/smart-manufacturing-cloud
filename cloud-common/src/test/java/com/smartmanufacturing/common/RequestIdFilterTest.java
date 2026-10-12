package com.smartmanufacturing.common;

import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** RequestIdFilter 单元测试：保护关联标识的校验、传播以及 MDC 清理，避免日志被注入或串号。 */
class RequestIdFilterTest {
    private final RequestIdFilter filter = new RequestIdFilter();

    /** 执行过滤器并捕获下游执行期间 MDC 中的请求标识。 */
    private String run(MockHttpServletRequest req, MockHttpServletResponse res) throws Exception {
        AtomicReference<String> seen = new AtomicReference<>();
        FilterChain chain = (rq, rs) -> seen.set(MDC.get("requestId"));
        filter.doFilter(req, res, chain);
        return seen.get();
    }

    /** 合法的上游标识原样沿用，并写入响应头和 MDC。 */
    @Test @DisplayName("合法请求标识被沿用")
    void validIdIsPropagated() throws Exception {
        var req = new MockHttpServletRequest(); req.addHeader(RequestIdFilter.HEADER, "abc-123");
        var res = new MockHttpServletResponse();
        assertThat(run(req, res)).isEqualTo("abc-123");
        assertThat(res.getHeader(RequestIdFilter.HEADER)).isEqualTo("abc-123");
    }

    /** 缺失标识时生成 UUID，保证每个请求都可关联。 */
    @Test @DisplayName("缺失时生成 UUID")
    void missingIdIsGenerated() throws Exception {
        var res = new MockHttpServletResponse();
        String id = run(new MockHttpServletRequest(), res);
        assertThat(id).matches("[0-9a-f-]{36}");
        assertThat(res.getHeader(RequestIdFilter.HEADER)).isEqualTo(id);
    }

    /** 含换行等非法字符或超长的标识必须被替换，防止日志注入。 */
    @Test @DisplayName("非法或超长标识被替换")
    void invalidIdIsReplaced() throws Exception {
        for (String bad : new String[] {"evil\nINFO forged", "a".repeat(65), ""}) {
            var req = new MockHttpServletRequest(); req.addHeader(RequestIdFilter.HEADER, bad);
            assertThat(run(req, new MockHttpServletResponse())).isNotEqualTo(bad).matches("[0-9a-f-]{36}");
        }
    }

    /** 下游抛异常时 MDC 也必须清理，避免线程复用时串联到下一个请求。 */
    @Test @DisplayName("异常时仍清理 MDC")
    void mdcClearedEvenOnException() {
        FilterChain failing = (rq, rs) -> { throw new IllegalStateException("boom"); };
        assertThatThrownBy(() -> filter.doFilter(new MockHttpServletRequest(), new MockHttpServletResponse(), failing))
            .isInstanceOf(IllegalStateException.class);
        assertThat(MDC.get("requestId")).isNull();
    }
}
