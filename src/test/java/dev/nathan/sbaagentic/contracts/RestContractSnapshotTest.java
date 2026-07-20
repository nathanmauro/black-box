package dev.nathan.sbaagentic.contracts;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.junit.jupiter.api.Test;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.io.ClassPathResource;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:sqlite:target/rest-contract-snapshot-test.db",
        "sba.local-ai.enabled=false",
        "sba.summary.backend=local",
        "sba.elasticsearch.enabled=false",
        "sba.ask.embedding-enabled=false"
})
class RestContractSnapshotTest {

    @Autowired
    @Qualifier("requestMappingHandlerMapping")
    RequestMappingHandlerMapping handlerMapping;

    @Autowired
    ObjectMapper objectMapper;

    @Test
    void applicationMappingsMatchTheFrozenSnapshot() throws IOException {
        assertThat(applicationMappings()).containsExactlyElementsOf(resourceLines("contracts/rest-mappings.txt"));
    }

    @Test
    void everyApiMappingHasACompleteContractMatrixRow() throws IOException {
        JsonNode matrix = objectMapper.readTree(new ClassPathResource("contracts/rest-contract-matrix.json").getInputStream());
        assertThat(matrix.isArray()).isTrue();

        Set<String> rows = new TreeSet<>();
        for (JsonNode row : matrix) {
            assertThat(row.fieldNames()).toIterable().containsExactlyInAnyOrder(
                    "method",
                    "path",
                    "requiredInputs",
                    "optionalInputs",
                    "defaults",
                    "clamps",
                    "successStatus",
                    "errorStatus",
                    "contentType",
                    "requestFields",
                    "responseFields",
                    "characterizationTest");
            row.fields().forEachRemaining(field -> assertThat(field.getValue().isNull()).isFalse());
            assertThat(row.path("characterizationTest").asText()).isNotBlank();
            assertThat(rows.add(row.path("method").asText() + " " + row.path("path").asText())).isTrue();
        }

        assertThat(rows).containsExactlyElementsOf(applicationMappings().stream()
                .filter(mapping -> mapping.contains(" /api/"))
                .collect(java.util.stream.Collectors.toCollection(TreeSet::new)));
    }

    private Set<String> applicationMappings() {
        Set<String> mappings = new TreeSet<>();
        handlerMapping.getHandlerMethods().forEach((mapping, handler) -> {
            if (!handler.getBeanType().getPackageName().startsWith("dev.nathan.sbaagentic")) {
                return;
            }
            Set<RequestMethod> methods = mapping.getMethodsCondition().getMethods();
            Set<String> methodNames = methods.isEmpty()
                    ? Set.of("ANY")
                    : methods.stream().map(Enum::name).collect(java.util.stream.Collectors.toSet());
            for (String path : mapping.getPatternValues()) {
                for (String method : methodNames) {
                    mappings.add(method + " " + path);
                }
            }
        });
        return mappings;
    }

    private static List<String> resourceLines(String path) throws IOException {
        String text = new ClassPathResource(path).getContentAsString(StandardCharsets.UTF_8);
        return text.lines().filter(line -> !line.isBlank() && !line.startsWith("#")).sorted().toList();
    }
}
