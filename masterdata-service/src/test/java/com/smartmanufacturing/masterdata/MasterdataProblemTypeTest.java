package com.smartmanufacturing.masterdata;
import com.smartmanufacturing.common.BusinessException;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletRequest;
import java.nio.file.*;
import java.util.*;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import static org.assertj.core.api.Assertions.*;

/** Problem type 枚举守卫（#71）：源码中的每个主数据业务码都已登记；type 唯一且为 kebab-case；契约文档列出全部 type。 */
class MasterdataProblemTypeTest {
    private static final Pattern THROW = Pattern.compile("(?:Errors\\.(?:notFound|conflict|rule)|BusinessException)\\(\\s*\"([A-Z_]+)\"");

    @Test void everyBusinessCodeInSourceIsRegistered() throws Exception {
        List<String> missing = new ArrayList<>();
        int found = 0;
        try (Stream<Path> files = Files.walk(Path.of("src/main/java"))) {
            for (Path f : files.filter(p -> p.toString().endsWith(".java")).toList()) {
                var m = THROW.matcher(Files.readString(f));
                while (m.find()) {
                    found++;
                    if (MasterdataProblemType.ofCode(m.group(1)) == null) missing.add(f.getFileName() + ": " + m.group(1));
                }
            }
        }
        assertThat(found).isGreaterThan(30);
        assertThat(missing).isEmpty();
    }

    @Test void typesUniqueKebabAndSharedWithWorkorder() {
        assertThat(Arrays.stream(MasterdataProblemType.values()).map(MasterdataProblemType::type)).doesNotHaveDuplicates()
            .allMatch(t -> t.matches("[a-z]+(-[a-z]+)*"));
        assertThat(MasterdataProblemType.MASTERDATA_IN_USE.type()).isEqualTo("masterdata-in-use");
        // 与工单服务同名的通用 type 文案一致
        assertThat(MasterdataProblemType.VALIDATION_ERROR.title()).isEqualTo("请求参数无效");
        assertThat(MasterdataProblemType.ACCESS_DENIED.title()).isEqualTo(com.smartmanufacturing.common.SecurityProblem.ACCESS_DENIED.title());
        assertThat(MasterdataProblemType.AUTHENTICATION_REQUIRED.title()).isEqualTo(com.smartmanufacturing.common.SecurityProblem.AUTHENTICATION_REQUIRED.title());
    }

    /** docs/api/masterdata-openapi.yaml 的 ProblemType 枚举与代码完全一致。 */
    @Test void contractProblemTypesMatchEnum() throws Exception {
        String yaml = Files.readString(Path.of("../docs/api/masterdata-openapi.yaml"));
        int start = yaml.indexOf("    ProblemType:");
        assertThat(start).isPositive();
        String block = yaml.substring(start, yaml.indexOf("\n    Problem:\n", start));
        List<String> contract = new ArrayList<>();
        var m = Pattern.compile("(?m)^        - ([a-z-]+)$").matcher(block);
        while (m.find()) contract.add(m.group(1));
        assertThat(contract).containsExactlyInAnyOrderElementsOf(Arrays.stream(MasterdataProblemType.values()).map(MasterdataProblemType::type).toList());
    }

    @Test void unregisteredCodeBecomesInternalError() {
        var r = new MasterdataProblemHandler().business(new BusinessException("NOT_REGISTERED", "x", HttpStatus.CONFLICT), new MockHttpServletRequest());
        assertThat(r.getStatusCode().value()).isEqualTo(500);
        assertThat(r.getHeaders().getContentType()).isEqualTo(MediaType.APPLICATION_PROBLEM_JSON);
        assertThat(r.getBody().getType().toString()).isEqualTo("internal-error");
        assertThat(r.getBody().getDetail()).isEqualTo("服务暂时不可用");
    }

    @Test void unexpectedExceptionIsInternalErrorWithoutLeak() {
        var r = new MasterdataProblemHandler().unexpected(new IllegalStateException("secret"), new MockHttpServletRequest());
        assertThat(r.getStatusCode().value()).isEqualTo(500);
        assertThat(r.getBody().getType().toString()).isEqualTo("internal-error");
        assertThat(r.getBody().getProperties()).containsEntry("code", "INTERNAL_ERROR");
        assertThat(r.getBody().getDetail()).doesNotContain("secret");
    }

    @Test void inUseCarriesReferenceDataUnderTopLevelType() {
        var r = new MasterdataProblemHandler().business(Errors.inUse("BOM", "被引用", List.of("A/B")), new MockHttpServletRequest());
        assertThat(r.getStatusCode().value()).isEqualTo(409);
        assertThat(r.getBody().getType().toString()).isEqualTo("masterdata-in-use");
        @SuppressWarnings("unchecked") Map<String, Object> data = (Map<String, Object>) r.getBody().getProperties().get("data");
        assertThat(data).containsEntry("referenceType", "BOM").containsEntry("references", List.of("A/B")).doesNotContainKey("type");
    }

    @Test void framework4xxWithoutDetailGetsDefaultDetail() {
        var ex = new org.springframework.web.ErrorResponseException(HttpStatus.NOT_ACCEPTABLE);
        var r = new MasterdataProblemHandler().unexpected(ex, new MockHttpServletRequest());
        assertThat(r.getStatusCode().value()).isEqualTo(406);
        assertThat(r.getBody().getType().toString()).isEqualTo("validation-error");
        assertThat(r.getBody().getDetail()).isEqualTo("请求不被接受");
    }
}
