package dev.nathan.sbaagentic.memory;

import java.util.ArrayList;
import java.util.List;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "sba.ask")
public class MemoryRetrievalProperties {

    private String memoryIndex = "agent-memory";
    private String vectorField = "vector";
    private List<String> searchFields = new ArrayList<>(List.of(
            "text^4", "content^4", "summary^3", "title^3", "source_path^2", "path^2",
            "sourcePath^2", "source", "corpus", "project", "session_id", "sessionId",
            "client_session_id", "clientSessionId"));

    public String getMemoryIndex() { return memoryIndex; }
    public void setMemoryIndex(String memoryIndex) { this.memoryIndex = memoryIndex; }
    public String getVectorField() { return vectorField; }
    public void setVectorField(String vectorField) { this.vectorField = vectorField; }
    public List<String> getSearchFields() { return searchFields; }
    public void setSearchFields(List<String> searchFields) {
        this.searchFields = searchFields == null ? new ArrayList<>() : searchFields;
    }
}
