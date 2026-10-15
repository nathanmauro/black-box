package dev.nathan.sbaagentic.contracts;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.nathan.sbaagentic.memory.internal.adapter.in.mcp.CompactSearchMcpTools;
import dev.nathan.sbaagentic.memory.internal.adapter.in.mcp.MemoryMcpTools;
import dev.nathan.sbaagentic.project.internal.adapter.in.mcp.ProjectMcpTools;
import dev.nathan.sbaagentic.summary.internal.adapter.in.mcp.SummaryMcpTools;
import java.io.IOException;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.core.io.ClassPathResource;

/** Agents discover a stable set of MCP tools and input schemas through one callback provider. */
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

    @Autowired
    ApplicationContext applicationContext;

    @Autowired
    ObjectMapper objectMapper;

    @Autowired
    @Qualifier("agenticToolCallbacks")
    ToolCallbackProvider callbackProvider;

    @Test
    void toolNamesAndInputSchemasMatchTheFrozenSnapshot() throws IOException {
        JsonNode actual = normalizedDefinitions();
        java.nio.file.Path snapshot = java.nio.file.Path.of("src/test/resources/contracts/mcp-tools.json");
        if (Boolean.getBoolean("contracts.update")) {
            List<String> lines = new ArrayList<>();
            for (JsonNode definition : actual) lines.add("  " + objectMapper.writeValueAsString(definition));
            java.nio.file.Files.writeString(snapshot, "[\n" + String.join(",\n", lines) + "\n]\n");
        }
        JsonNode expected = Boolean.getBoolean("contracts.update")
                ? objectMapper.readTree(snapshot.toFile())
                : objectMapper.readTree(new ClassPathResource("contracts/mcp-tools.json").getInputStream());
        assertThat(actual).isEqualTo(expected);
        assertThat(callbackProvider.getToolCallbacks()).hasSize(13);
    }

    @Test
    void callbackQualifierAndRemainingToolsStayStable() {
        assertThat(applicationContext.getBean("agenticToolCallbacks", ToolCallbackProvider.class))
                .isSameAs(callbackProvider);

        List<String> annotatedNames = new ArrayList<>();
        for (Class<?> toolGroup : List.of(
                CompactSearchMcpTools.class, MemoryMcpTools.class, SummaryMcpTools.class, ProjectMcpTools.class)) {
            for (Method method : toolGroup.getDeclaredMethods()) {
                Tool tool = method.getAnnotation(Tool.class);
                if (tool == null) {
                    continue;
                }
                annotatedNames.add(method.getName());
            }
        }
        assertThat(annotatedNames).hasSize(13);
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
}
