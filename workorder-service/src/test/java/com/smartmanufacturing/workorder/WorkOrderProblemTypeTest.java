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
}
