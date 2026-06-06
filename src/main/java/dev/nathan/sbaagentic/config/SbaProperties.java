package dev.nathan.sbaagentic.config;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "sba")
public class SbaProperties {

    private final LocalAi localAi = new LocalAi();

    private final Summary summary = new Summary();

    private final Exports exports = new Exports();

    private final Elasticsearch elasticsearch = new Elasticsearch();

    private final Ask ask = new Ask();

    private final Ingestion ingestion = new Ingestion();

    public LocalAi getLocalAi() {
        return localAi;
    }

    public Summary getSummary() {
        return summary;
    }

    public Exports getExports() {
        return exports;
    }

    public Elasticsearch getElasticsearch() {
        return elasticsearch;
    }

    public Ask getAsk() {
        return ask;
    }

    public Ingestion getIngestion() {
        return ingestion;
    }

    public static class LocalAi {
        private boolean enabled = true;
        private String baseUrl = "http://localhost:1234";
        private String chatPath = "/v1/chat/completions";
        private String model = "local-model";
        private String apiKey = "lm-studio";
        private Duration timeout = Duration.ofSeconds(30);
        private int maxTokens = 320;
        // Per-request char budget for the local model. 8000 chars ~= 2.7k tokens at the dense ~3 chars/token
        // of a raw transcript; that leaves real headroom on a 4096-token context after the max-tokens output
        // reservation, the system prompt, and chat-template overhead. Larger transcripts are map-reduced, not
        // rejected. Raise this if the loaded model has a bigger context window.
        private int maxInputChars = 8_000;

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public String getBaseUrl() {
            return baseUrl;
        }

        public void setBaseUrl(String baseUrl) {
            this.baseUrl = baseUrl;
        }

        public String getChatPath() {
            return chatPath;
        }

        public void setChatPath(String chatPath) {
            this.chatPath = chatPath;
        }

        public String getModel() {
            return model;
        }

        public void setModel(String model) {
            this.model = model;
        }

        public String getApiKey() {
            return apiKey;
        }

        public void setApiKey(String apiKey) {
            this.apiKey = apiKey;
        }

        public Duration getTimeout() {
            return timeout;
        }

        public void setTimeout(Duration timeout) {
            this.timeout = timeout;
        }

        public int getMaxTokens() {
            return maxTokens;
        }

        public void setMaxTokens(int maxTokens) {
            this.maxTokens = maxTokens;
        }

        public int getMaxInputChars() {
            return maxInputChars;
        }

        public void setMaxInputChars(int maxInputChars) {
            this.maxInputChars = maxInputChars;
        }
    }

    public static class Summary {
        private String backend = "external";
        private String externalCommand = "scripts/summarize-with-codex.sh";
        private Duration timeout = Duration.ofMinutes(10);

        public String getBackend() {
            return backend;
        }

        public void setBackend(String backend) {
            this.backend = backend;
        }

        public String getExternalCommand() {
            return externalCommand;
        }

        public void setExternalCommand(String externalCommand) {
            this.externalCommand = externalCommand;
        }

        public Duration getTimeout() {
            return timeout;
        }

        public void setTimeout(Duration timeout) {
            this.timeout = timeout;
        }
    }

    public static class Exports {
        private List<Target> targets = new ArrayList<>(List.of(Target.obsidianDefault()));

        public List<Target> getTargets() {
            return targets;
        }

        public void setTargets(List<Target> targets) {
            this.targets = targets == null ? new ArrayList<>() : targets;
        }
    }

    public static class Target {
        private String id;
        private String label;
        private String type = "markdown-file";
        private boolean enabled = true;
        private String directory;
        private String template = "classpath:/exports/summary-markdown.mustache";
        private String subdirectoryTemplate = "{{month}}";
        private String filenameTemplate = "{{date}}-{{slug}}-{{shortId}}.md";

        static Target obsidianDefault() {
            Target target = new Target();
            target.setId("obsidian");
            target.setLabel("Obsidian");
            target.setType("markdown-file");
            target.setDirectory(System.getProperty("user.home")
                    + "/Notes/obsidian/99 System/Black Box Summaries");
            return target;
        }

        public String getId() {
            return id;
        }

        public void setId(String id) {
            this.id = id;
        }

        public String getLabel() {
            return label;
        }

        public void setLabel(String label) {
            this.label = label;
        }

        public String getType() {
            return type;
        }

        public void setType(String type) {
            this.type = type;
        }

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public String getDirectory() {
            return directory;
        }

        public void setDirectory(String directory) {
            this.directory = directory;
        }

        public String getTemplate() {
            return template;
        }

        public void setTemplate(String template) {
            this.template = template;
        }

        public String getSubdirectoryTemplate() {
            return subdirectoryTemplate;
        }

        public void setSubdirectoryTemplate(String subdirectoryTemplate) {
            this.subdirectoryTemplate = subdirectoryTemplate;
        }

        public String getFilenameTemplate() {
            return filenameTemplate;
        }

        public void setFilenameTemplate(String filenameTemplate) {
            this.filenameTemplate = filenameTemplate;
        }
    }

    public static class Elasticsearch {
        private boolean enabled;
        private String baseUrl = "http://localhost:9200";
        private String indexName = "sba-agentic-events";
        private Duration timeout = Duration.ofSeconds(5);

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public String getBaseUrl() {
            return baseUrl;
        }

        public void setBaseUrl(String baseUrl) {
            this.baseUrl = baseUrl;
        }

        public String getIndexName() {
            return indexName;
        }

        public void setIndexName(String indexName) {
            this.indexName = indexName;
        }

        public Duration getTimeout() {
            return timeout;
        }

        public void setTimeout(Duration timeout) {
            this.timeout = timeout;
        }
    }

    public static class Ask {
        private String memoryIndex = "agent-memory";
        private String vectorField = "vector";
        private List<String> searchFields = new ArrayList<>(List.of(
                "text^4",
                "content^4",
                "summary^3",
                "title^3",
                "source_path^2",
                "path^2",
                "sourcePath^2",
                "source",
                "corpus",
                "project",
                "session_id",
                "sessionId",
                "client_session_id",
                "clientSessionId"));
        private boolean embeddingEnabled = true;
        private String embeddingBaseUrl = "http://localhost:11434";
        private String embeddingPath = "/api/embeddings";
        private String embeddingModel = "nomic-embed-text";
        private int embeddingDimensions = 768;
        private Duration embeddingTimeout = Duration.ofSeconds(5);
        private int defaultAskCitations = 6;
        private int defaultRetrieveResults = 10;
        private int answerMaxTokens = 640;

        public String getMemoryIndex() {
            return memoryIndex;
        }

        public void setMemoryIndex(String memoryIndex) {
            this.memoryIndex = memoryIndex;
        }

        public String getVectorField() {
            return vectorField;
        }

        public void setVectorField(String vectorField) {
            this.vectorField = vectorField;
        }

        public List<String> getSearchFields() {
            return searchFields;
        }

        public void setSearchFields(List<String> searchFields) {
            this.searchFields = searchFields == null ? new ArrayList<>() : searchFields;
        }

        public boolean isEmbeddingEnabled() {
            return embeddingEnabled;
        }

        public void setEmbeddingEnabled(boolean embeddingEnabled) {
            this.embeddingEnabled = embeddingEnabled;
        }

        public String getEmbeddingBaseUrl() {
            return embeddingBaseUrl;
        }

        public void setEmbeddingBaseUrl(String embeddingBaseUrl) {
            this.embeddingBaseUrl = embeddingBaseUrl;
        }

        public String getEmbeddingPath() {
            return embeddingPath;
        }

        public void setEmbeddingPath(String embeddingPath) {
            this.embeddingPath = embeddingPath;
        }

        public String getEmbeddingModel() {
            return embeddingModel;
        }

        public void setEmbeddingModel(String embeddingModel) {
            this.embeddingModel = embeddingModel;
        }

        public int getEmbeddingDimensions() {
            return embeddingDimensions;
        }

        public void setEmbeddingDimensions(int embeddingDimensions) {
            this.embeddingDimensions = embeddingDimensions;
        }

        public Duration getEmbeddingTimeout() {
            return embeddingTimeout;
        }

        public void setEmbeddingTimeout(Duration embeddingTimeout) {
            this.embeddingTimeout = embeddingTimeout;
        }

        public int getDefaultAskCitations() {
            return defaultAskCitations;
        }

        public void setDefaultAskCitations(int defaultAskCitations) {
            this.defaultAskCitations = defaultAskCitations;
        }

        public int getDefaultRetrieveResults() {
            return defaultRetrieveResults;
        }

        public void setDefaultRetrieveResults(int defaultRetrieveResults) {
            this.defaultRetrieveResults = defaultRetrieveResults;
        }

        public int getAnswerMaxTokens() {
            return answerMaxTokens;
        }

        public void setAnswerMaxTokens(int answerMaxTokens) {
            this.answerMaxTokens = answerMaxTokens;
        }
    }

    public static class Ingestion {
        private int maxTextLength = 20_000;

        public int getMaxTextLength() {
            return maxTextLength;
        }

        public void setMaxTextLength(int maxTextLength) {
            this.maxTextLength = maxTextLength;
        }
    }
}
