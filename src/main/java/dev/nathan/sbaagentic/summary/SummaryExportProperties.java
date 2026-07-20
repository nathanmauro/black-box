package dev.nathan.sbaagentic.summary;

import java.util.ArrayList;
import java.util.List;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "sba.exports")
public class SummaryExportProperties {

    private List<Target> targets = new ArrayList<>(List.of(Target.obsidianDefault()));

    public List<Target> getTargets() { return targets; }
    public void setTargets(List<Target> targets) {
        this.targets = targets == null ? new ArrayList<>() : targets;
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

        private static Target obsidianDefault() {
            Target target = new Target();
            target.setId("obsidian");
            target.setLabel("Obsidian");
            target.setType("markdown-file");
            return target;
        }

        public String getId() { return id; }
        public void setId(String id) { this.id = id; }
        public String getLabel() { return label; }
        public void setLabel(String label) { this.label = label; }
        public String getType() { return type; }
        public void setType(String type) { this.type = type; }
        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean enabled) { this.enabled = enabled; }
        public String getDirectory() { return directory; }
        public void setDirectory(String directory) { this.directory = directory; }
        public String getTemplate() { return template; }
        public void setTemplate(String template) { this.template = template; }
        public String getSubdirectoryTemplate() { return subdirectoryTemplate; }
        public void setSubdirectoryTemplate(String subdirectoryTemplate) {
            this.subdirectoryTemplate = subdirectoryTemplate;
        }
        public String getFilenameTemplate() { return filenameTemplate; }
        public void setFilenameTemplate(String filenameTemplate) { this.filenameTemplate = filenameTemplate; }
    }
}
