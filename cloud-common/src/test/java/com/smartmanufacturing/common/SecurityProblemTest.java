package com.smartmanufacturing.common;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

/** SecurityProblem 响应体：instance 只取路径；没有请求或路径为空时整个省略，不写 null。 */
class SecurityProblemTest {
    @Test void instanceIsPathWithoutQuery() {
        var request = new MockHttpServletRequest("GET", "/devices/config");
        request.setQueryString("token=secret");
        assertThat(SecurityProblem.ACCESS_DENIED.body(request)).containsEntry("instance", "/devices/config");
    }

    @Test void instanceOmittedWhenRequestMissing() {
        var body = SecurityProblem.AUTHENTICATION_REQUIRED.body(null);
        assertThat(body).doesNotContainKey("instance").containsKeys("type", "title", "status", "detail");
    }

    @Test void instanceOmittedWhenUriEmpty() {
        var request = new MockHttpServletRequest("GET", "");
        request.setRequestURI("");
        assertThat(SecurityProblem.ACCESS_DENIED.body(request)).doesNotContainKey("instance");
    }
}
