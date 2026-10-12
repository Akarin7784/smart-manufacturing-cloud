package com.smartmanufacturing.quality;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.networknt.schema.JsonSchema;
import com.networknt.schema.JsonSchemaFactory;
import com.networknt.schema.SpecVersion;
import com.networknt.schema.ValidationMessage;
import org.springframework.test.web.servlet.MvcResult;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import java.util.stream.Collectors;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * 契约校验工具：读取仓库中的 docs/api/quality-openapi.yaml（唯一契约来源），按（路径模板、方法、状态码）找到响应定义，
 * 校验响应的媒体类型与响应体是否符合其 schema。
 * <p>
 * OpenAPI 3.1 的 schema 即 JSON Schema 2020-12：把 components.schemas 放入 $defs 并改写 $ref 后，直接交给 networknt 校验器。
 * 未在契约中声明的状态码直接判定失败，防止实现返回契约之外的响应。
 */
final class OpenApiContract {
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final JsonNode DOC = load();
    private static final JsonSchemaFactory FACTORY = JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V202012);

    private OpenApiContract() {}

    /** 从模块目录向上定位契约文件，兼容在仓库根或模块目录执行 Maven。 */
    private static JsonNode load() {
        Path p = Path.of("docs/api/quality-openapi.yaml");
        if (!Files.exists(p)) p = Path.of("../docs/api/quality-openapi.yaml");
        try (Reader r = Files.newBufferedReader(p, StandardCharsets.UTF_8)) {
            Object yaml = new Yaml(new LoaderOptions()).load(r);
            return MAPPER.valueToTree(yaml);
        } catch (Exception e) {
            throw new IllegalStateException("无法读取契约文件 " + p.toAbsolutePath(), e);
        }
    }

    /**
     * 断言响应符合契约。
     * @param pathTemplate 契约中的路径模板，如 /api/quality/ncrs/{ncrNo}
     * @param method       小写 HTTP 方法
     * @return 解析后的响应体，便于调用方继续断言
     */
    static JsonNode assertConforms(String pathTemplate, String method, MvcResult result) throws Exception {
        int status = result.getResponse().getStatus();
        JsonNode op = DOC.path("paths").path(pathTemplate).path(method);
        assertThat(op.isMissingNode()).as("契约中没有 %s %s", method, pathTemplate).isFalse();
        JsonNode response = op.path("responses").path(String.valueOf(status));
        assertThat(response.isMissingNode()).as("契约未声明 %s %s 的 %d 响应", method, pathTemplate, status).isFalse();
        if (response.has("$ref")) response = resolve(response.get("$ref").asText());
        JsonNode content = response.path("content");
        String contentType = result.getResponse().getContentType();
        assertThat(contentType).as("%s %s %d 缺少 Content-Type", method, pathTemplate, status).isNotNull();
        String mediaType = contentType.split(";")[0].trim();
        assertThat(content.has(mediaType)).as("%s %s %d 的媒体类型 %s 不在契约中（契约：%s）", method, pathTemplate, status, mediaType, content.fieldNames()).isTrue();
        JsonNode body = MAPPER.readTree(result.getResponse().getContentAsString(StandardCharsets.UTF_8));
        JsonSchema schema = FACTORY.getSchema(schemaFor(content.path(mediaType).path("schema")));
        Set<ValidationMessage> errors = schema.validate(body);
        assertThat(errors).as("%s %s %d 响应不符合契约：%s\n响应体：%s", method, pathTemplate, status,
            errors.stream().map(ValidationMessage::getMessage).collect(Collectors.joining("; ")), body).isEmpty();
        return body;
    }

    /** 构造可独立校验的 JSON Schema：目标 schema + $defs（全部 components.schemas），并把 $ref 改写到 $defs。 */
    private static JsonNode schemaFor(JsonNode target) throws Exception {
        ObjectNode root = MAPPER.createObjectNode();
        root.put("$schema", "https://json-schema.org/draft/2020-12/schema");
        root.setAll((ObjectNode) target.deepCopy());
        root.set("$defs", DOC.path("components").path("schemas").deepCopy());
        String text = MAPPER.writeValueAsString(root).replace("#/components/schemas/", "#/$defs/");
        return MAPPER.readTree(text);
    }

    private static JsonNode resolve(String ref) {
        String[] parts = ref.replaceFirst("^#/", "").split("/");
        JsonNode node = DOC;
        for (String part : parts) node = node.path(part);
        return node;
    }

    /** 断言一条 outbox 事件载荷符合契约 components.schemas 中对应的事件 schema（如 NcrOpenedEvent）。 */
    static void assertEventConforms(String eventType, String payload) throws Exception {
        JsonNode body = MAPPER.readTree(payload);
        ObjectNode ref = MAPPER.createObjectNode();
        ref.put("$ref", "#/components/schemas/" + eventType + "Event");
        assertThat(DOC.path("components").path("schemas").has(eventType + "Event")).as("契约中没有事件 %s", eventType).isTrue();
        Set<ValidationMessage> errors = FACTORY.getSchema(schemaFor(ref)).validate(body);
        assertThat(errors).as("事件 %s 不符合契约：%s\n载荷：%s", eventType,
            errors.stream().map(ValidationMessage::getMessage).collect(Collectors.joining("; ")), body).isEmpty();
    }

    /**
     * 契约中 ProblemType 枚举的已实现取值（用于断言测试覆盖了每一种业务错误）。
     * 扣除 {@code x-planned} 中列出的类型：契约 PR 先于实现合入，这些类型由实现 PR 移出 x-planned 后再纳入覆盖断言。
     */
    static Set<String> problemTypes() {
        var schema = DOC.path("components").path("schemas").path("ProblemType");
        var type = new com.fasterxml.jackson.core.type.TypeReference<Set<String>>() {};
        Set<String> all = new java.util.TreeSet<>(MAPPER.convertValue(schema.path("enum"), type));
        // 未声明 x-planned 时 path() 返回缺失节点，转换结果为 null，此时不扣除任何类型。
        Set<String> planned = MAPPER.convertValue(schema.path("x-planned"), type);
        if (planned != null) all.removeAll(planned);
        return all;
    }

}
