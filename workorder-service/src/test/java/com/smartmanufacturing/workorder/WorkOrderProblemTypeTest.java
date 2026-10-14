package com.smartmanufacturing.workorder;
import org.junit.jupiter.api.Test;
import java.nio.file.*;
import java.util.*;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import static org.assertj.core.api.Assertions.*;

/** Problem type 枚举守卫：源码中出现的每个业务码都必须已登记，且登记的 HTTP 状态与抛出处一致；type 不得重复。 */
class WorkOrderProblemTypeTest {
    private static final Pattern THROW = Pattern.compile("BusinessException\\(\\s*\"([A-Z_]+)\"[^;]*?HttpStatus\\.([A-Z_]+)", Pattern.DOTALL);

    @Test void everyBusinessCodeInSourceIsRegisteredWithSameStatus() throws Exception {
        List<String> problems = new ArrayList<>();
        int found = 0;
        try (Stream<Path> files = Files.walk(Path.of("src/main/java"))) {
            for (Path f : files.filter(p -> p.toString().endsWith(".java")).toList()) {
                var m = THROW.matcher(Files.readString(f));
                while (m.find()) {
                    found++;
                    WorkOrderProblemType t = WorkOrderProblemType.ofCode(m.group(1));
                    if (t == null) problems.add(f.getFileName() + ": 业务码 " + m.group(1) + " 未登记");
                    else if (!t.status().name().equals(m.group(2))) problems.add(f.getFileName() + ": 业务码 " + m.group(1) + " 状态 " + m.group(2) + " 与枚举 " + t.status() + " 不一致");
                }
            }
        }
        assertThat(found).as("应扫描到业务码").isGreaterThan(10);
        assertThat(problems).isEmpty();
    }

    @Test void typesAreUniqueAndDesignStatusesFixed() {
        assertThat(Arrays.stream(WorkOrderProblemType.values()).map(WorkOrderProblemType::type)).doesNotHaveDuplicates();
        assertThat(WorkOrderProblemType.OVER_REPORT.status().value()).isEqualTo(422);
        assertThat(WorkOrderProblemType.DOWNSTREAM_REPORTED.status().value()).isEqualTo(409);
        assertThat(WorkOrderProblemType.WORKORDER_CLOSED.status().value()).isEqualTo(409);
        assertThat(WorkOrderProblemType.OPERATIONS_UNREPORTED.status().value()).isEqualTo(409);
        assertThat(WorkOrderProblemType.OVER_REPORT.type()).isEqualTo("over-report");
    }

    /** 契约 docs/api/workorder-openapi.yaml 的 ProblemType 枚举必须与代码枚举完全一致。 */
    @Test void contractProblemTypesMatchEnum() throws Exception {
        String yaml = Files.readString(Path.of("../docs/api/workorder-openapi.yaml"));
        int start = yaml.indexOf("    ProblemType:");
        String block = yaml.substring(start, yaml.indexOf("\n    Problem:\n", start));
        String enumPart = block.substring(block.indexOf("      enum:"));
        List<String> contract = new ArrayList<>();
        var m = Pattern.compile("(?m)^        - ([a-z-]+)$").matcher(enumPart);
        while (m.find()) contract.add(m.group(1));
        assertThat(contract).containsExactlyInAnyOrderElementsOf(Arrays.stream(WorkOrderProblemType.values()).map(WorkOrderProblemType::type).toList());
        for (WorkOrderProblemType p : WorkOrderProblemType.values())
            assertThat(block).as("契约表格应列出 %s 及其状态码", p.type()).contains("| `" + p.type() + "` | " + p.status().value() + " |");
    }

    /** docs/observability.md 第 5 节列出的 result 取值必须覆盖每个枚举值（下划线写法）。 */
    @Test void observabilityDocListsEveryResultValue() throws Exception {
        String doc = Files.readString(Path.of("../docs/observability.md"));
        String row = doc.lines().filter(l -> l.startsWith("| `mes_workorder_report_total`")).findFirst().orElseThrow();
        for (WorkOrderProblemType p : WorkOrderProblemType.values())
            assertThat(row).as("observability.md 应列出 result=%s", ReportingMetrics.resultOf(p)).contains("`" + ReportingMetrics.resultOf(p) + "`");
    }

    /** 解析 docs/authentication.md 工单服务表（type -> title）。 */
    private static Map<String, String> documentedTitles() throws Exception {
        String doc = Files.readString(Path.of("../docs/authentication.md"));
        int start = doc.indexOf("### 工单服务 Problem 标题");
        assertThat(start).as("authentication.md 应有工单服务 Problem 标题表").isGreaterThanOrEqualTo(0);
        int end = doc.indexOf("\n## ", start);
        Map<String, String> table = new LinkedHashMap<>();
        var m = Pattern.compile("(?m)^\\| `([a-z-]+)` \\| ([^|]+?) \\|$").matcher(doc.substring(start, end < 0 ? doc.length() : end));
        while (m.find()) assertThat(table.put(m.group(1), m.group(2))).as("type %s 在表中重复", m.group(1)).isNull();
        return table;
    }

    /** 枚举登记的 title 必须与文档表逐项完全一致（双向：不多不少、文案相同）。 */
    @Test void titlesExactlyMatchDocumentedTable() throws Exception {
        Map<String, String> code = new LinkedHashMap<>();
        for (WorkOrderProblemType p : WorkOrderProblemType.values()) code.put(p.type(), p.title());
        assertThat(documentedTitles()).containsExactlyInAnyOrderEntriesOf(code);
    }

    /** title 为固定中文短语，不得退回枚举常量名；401/403 与 cloud-common SecurityProblem 同文案。 */
    @Test void titlesAreChineseAndSecurityTitlesShared() {
        for (WorkOrderProblemType p : WorkOrderProblemType.values()) {
            assertThat(p.title()).as("%s 的 title", p).isNotBlank().isNotEqualTo(p.name()).isNotEqualTo(p.code())
                .matches(".*[\\u4e00-\\u9fa5].*").doesNotContainPattern("[A-Z_]{3,}");
        }
        assertThat(WorkOrderProblemType.AUTHENTICATION_REQUIRED.title()).isEqualTo(com.smartmanufacturing.common.SecurityProblem.AUTHENTICATION_REQUIRED.title()).isEqualTo("需要认证");
        assertThat(WorkOrderProblemType.ACCESS_DENIED.title()).isEqualTo(com.smartmanufacturing.common.SecurityProblem.ACCESS_DENIED.title()).isEqualTo("没有访问权限");
        assertThat(WorkOrderProblemType.AUTHENTICATION_REQUIRED.type()).isEqualTo(com.smartmanufacturing.common.SecurityProblem.AUTHENTICATION_REQUIRED.type());
    }

    /** 处理器输出的 title 就是枚举登记值，同一 type 恒为同一 title，与 detail 无关。 */
    @Test void handlerEmitsFixedTitlePerType() {
        for (WorkOrderProblemType p : WorkOrderProblemType.values()) {
            var a = WorkOrderProblemHandler.problem(p, "原因甲", null).getBody();
            var b = WorkOrderProblemHandler.problem(p, "原因乙", null).getBody();
            assertThat(a.getTitle()).isEqualTo(p.title()).isEqualTo(b.getTitle());
            assertThat(a.getType().toString()).isEqualTo(p.type());
        }
    }

    /** 契约 ProblemType 表的 title 列与枚举一致。 */
    @Test void contractTableListsTitles() throws Exception {
        String yaml = Files.readString(Path.of("../docs/api/workorder-openapi.yaml"));
        for (WorkOrderProblemType p : WorkOrderProblemType.values())
            assertThat(yaml).as("契约表格应列出 %s 的 title", p.type()).contains("| `" + p.type() + "` | " + p.status().value() + " | " + p.title() + " |");
    }
}
