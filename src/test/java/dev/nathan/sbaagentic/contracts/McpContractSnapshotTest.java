package dev.nathan.sbaagentic.contracts;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.nathan.sbaagentic.memory.internal.adapter.in.mcp.CompactSearchMcpTools;
import dev.nathan.sbaagentic.memory.internal.adapter.in.mcp.MemoryMcpTools;
import dev.nathan.sbaagentic.summary.internal.adapter.in.mcp.SummaryMcpTools;
import dev.nathan.sbaagentic.workflow.internal.adapter.in.mcp.RestJsonToolCallResultConverter;
import dev.nathan.sbaagentic.workflow.internal.adapter.in.mcp.WorkflowMcpTools;
import java.io.IOException;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.core.io.ClassPathResource;

@SpringBootTest(
        properties = {
            "spring.datasource.url=jdbc:sqlite:${java.io.tmpdir}/bb-mcp-contract-snapshot-test-${random.uuid}.db",
            "sba.local-ai.enabled=false",
            "sba.summary.backend=local",
            "sba.elasticsearch.enabled=false",
            "sba.ask.embedding-enabled=false",
            "sba.memory.embedding.enabled=false"
        })
class McpContractSnapshotTest {

    private static final Set<String> REST_JSON_TOOLS = Set.of(
            "createSpec", "enqueueTask", "claimNextTask", "updateTaskStatus", "completeTask", "listTasks", "getSpec");

    @Autowired
    ApplicationContext applicationContext;

    @Autowired
    ObjectMapper objectMapper;

    @Autowired
    @Qualifier("agenticToolCallbacks")
    ToolCallbackProvider callbackProvider;

    @Test
    void toolNamesAndInputSchemasMatchTheFrozenSnapshot() throws IOException {
        JsonNode expected = objectMapper.readTree(new ClassPathResource("contracts/mcp-tools.json").getInputStream());
        assertThat(normalizedDefinitions()).isEqualTo(expected);
        assertThat(callbackProvider.getToolCallbacks()).hasSize(16);
    }

    @Test
    void callbackQualifierAndRestJsonConvertersStayStable() {
        assertThat(applicationContext.getBean("agenticToolCallbacks", ToolCallbackProvider.class))
                .isSameAs(callbackProvider);

        List<String> annotatedNames = new ArrayList<>();
        for (Class<?> toolGroup : List.of(
                CompactSearchMcpTools.class, MemoryMcpTools.class, SummaryMcpTools.class, WorkflowMcpTools.class)) {
            for (Method method : toolGroup.getDeclaredMethods()) {
                Tool tool = method.getAnnotation(Tool.class);
                if (tool == null) {
                    continue;
                }
                annotatedNames.add(method.getName());
                if (REST_JSON_TOOLS.contains(method.getName())) {
                    assertThat(tool.resultConverter()).isEqualTo(RestJsonToolCallResultConverter.class);
                }
            }
        }
        assertThat(annotatedNames).hasSize(16).containsAll(REST_JSON_TOOLS);
    }

    @Test
    void recallContextOutputContractAllowsNullableScoreWithoutChangingExistingFields() throws IOException {
        JsonNode records = objectMapper
                .readTree(new ClassPathResource("contracts/wire-fixtures.json").getInputStream())
                .path("records");
        assertRecallResultShape(records.path("RecallResult"));
        assertRecalledItemShape(records.path("RecalledItem"));
    }

    private ArrayNode normalizedDefinitions() throws IOException {
        ArrayNode definitions = objectMapper.createArrayNode();
        List<ToolCallback> callbacks = List.of(callbackProvider.getToolCallbacks()).stream()
                .sorted(Comparator.comparing(
                        callback -> callback.getToolDefinition().name()))
                .toList();
        for (ToolCallback callback : callbacks) {
            JsonNode schema = objectMapper.readTree(callback.getToolDefinition().inputSchema());
            ObjectNode definition = definitions.addObject();
            definition.put("name", callback.getToolDefinition().name());

            ArrayNode required = definition.putArray("required");
            if (schema.path("required").isArray()) {
                List<String> names = new ArrayList<>();
                schema.path("required").forEach(node -> names.add(node.asText()));
                names.stream().sorted().forEach(required::add);
            }

            ObjectNode properties = definition.putObject("properties");
            List<String> propertyNames = new ArrayList<>();
            schema.path("properties").fieldNames().forEachRemaining(propertyNames::add);
            for (String name : propertyNames.stream().sorted().toList()) {
                JsonNode property = schema.path("properties").path(name);
                ObjectNode normalized = properties.putObject(name);
                normalized.set("type", property.path("type"));
                if (property.has("items")) {
                    normalized.set("items", property.path("items"));
                }
                if (property.has("additionalProperties")) {
                    normalized.set("additionalProperties", property.path("additionalProperties"));
                }
            }
        }

        return definitions;
    }

    private static Set<String> fieldNames(JsonNode node) {
        Set<String> names = new java.util.TreeSet<>();
        node.fieldNames().forEachRemaining(names::add);

        return names;
    }

    private static void assertRecallResultShape(JsonNode result) {
        assertThat(fieldNames(result)).contains("scope", "withinHours", "kinds", "count", "items", "mode", "truncated");
        assertThat(result.path("scope").isTextual()).isTrue();
        assertThat(result.path("withinHours").isInt()).isTrue();
        assertThat(result.path("kinds").isArray()).isTrue();
        assertThat(result.path("count").isInt()).isTrue();
        assertThat(result.path("items").isArray()).isTrue();
        assertThat(result.path("mode").isTextual()).isTrue();
        assertThat(result.path("truncated").isBoolean()).isTrue();
    }

    private static void assertRecalledItemShape(JsonNode item) {
        assertThat(fieldNames(item))
                .contains(
                        "eventId",
                        "sessionId",
                        "kind",
                        "source",
                        "clientSessionId",
                        "repo",
                        "observedAt",
                        "headline",
                        "rationale",
                        "alternatives",
                        "confidence",
                        "openLoops",
                        "nextAction",
                        "toAgent",
                        "score");
        assertThat(item.path("eventId").isTextual()).isTrue();
        assertThat(item.path("sessionId").isTextual()).isTrue();
        assertThat(item.path("kind").isTextual()).isTrue();
        assertThat(item.path("source").isTextual()).isTrue();
        assertThat(item.path("clientSessionId").isTextual()).isTrue();
        assertThat(item.path("repo").isTextual()).isTrue();
        assertThat(item.path("observedAt").isTextual()).isTrue();
        assertThat(item.path("headline").isTextual()).isTrue();
        assertThat(item.path("alternatives").isArray()).isTrue();
        assertThat(item.path("openLoops").isArray()).isTrue();
        assertThat(item.path("nextAction").isTextual()).isTrue();
        assertThat(item.path("toAgent").isTextual()).isTrue();
        assertThat(item.path("score").isNumber() || item.path("score").isNull()).isTrue();
    }
}
