package com.smartmanufacturing.workorder;
import org.junit.jupiter.api.Test;
import java.nio.file.Files;
import java.nio.file.Path;
import static org.assertj.core.api.Assertions.assertThat;

/** 过渡建表脚本必须与正式迁移 V2 保持一致（仅多 IF NOT EXISTS），防止两份 DDL 漂移。rebase 到 #11 后随过渡脚本一起删除。 */
class TransitionalSchemaTest {
    @Test void transitionalScriptMirrorsV2() throws Exception {
        String v2 = Files.readString(Path.of("src/main/resources/db/migration/V2__workorder_release.sql"));
        String init = Files.readString(Path.of("src/main/resources/db/transitional/workorder-release-init.sql"));
        assertThat(v2).doesNotContain("CREATE TABLE IF NOT EXISTS");
        String body = init.substring(init.indexOf(v2.substring(0, 20)));
        assertThat(body).isEqualTo(v2.replace("CREATE TABLE ", "CREATE TABLE IF NOT EXISTS "));
    }
}
