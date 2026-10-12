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
}
