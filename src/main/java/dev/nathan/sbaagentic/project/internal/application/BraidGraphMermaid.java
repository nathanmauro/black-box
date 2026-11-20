package dev.nathan.sbaagentic.project.internal.application;

import dev.nathan.sbaagentic.project.internal.application.BraidGraph.Edge;
import dev.nathan.sbaagentic.project.internal.application.BraidGraph.Node;
import dev.nathan.sbaagentic.project.internal.domain.MermaidText;
import java.util.Map;

/**
 * Renders a {@link BraidGraph} as a Mermaid flowchart. Every statement is generated here: node ids
 * and edge relations are internal constants, and the only stored text is the already-redacted
 * labels, entity-encoded by {@link MermaidText} so they cannot leave their quotes.
 */
public final class BraidGraphMermaid {

    private static final Map<String, String[]> SHAPES = Map.of(
            BraidGraph.SESSION, new String[] {"[\"", "\"]"},
            BraidGraph.DECISION, new String[] {"{{\"", "\"}}"},
            BraidGraph.HANDOFF, new String[] {"([\"", "\"])"},
            BraidGraph.BRAID, new String[] {"((\"", "\"))"},
            BraidGraph.MISSING_SESSION, new String[] {"[\"", "\"]"});

    private BraidGraphMermaid() {}

    public static String render(BraidGraph graph) {
        StringBuilder out = new StringBuilder(
                256 + graph.nodes().size() * 64 + graph.edges().size() * 32);
        out.append("flowchart LR\n");
        out.append("  %% Black Box braid graph: ")
                .append(graph.nodes().size())
                .append(" nodes, ")
                .append(graph.edges().size())
                .append(" edges")
                .append(graph.truncated() ? ", truncated" : "")
                .append(". Labels are redacted.\n");
        out.append("  classDef session fill:#eef3ff,stroke:#4a5f99\n");
        out.append("  classDef decision fill:#fff6e0,stroke:#a37a00\n");
        out.append("  classDef handoff fill:#e8f7ee,stroke:#2f7a4d\n");
        out.append("  classDef braid fill:#f3e8ff,stroke:#6b3fa0\n");
        out.append("  classDef missing_session fill:#ffecec,stroke:#b03030,stroke-dasharray:4 3\n");
        for (Node node : graph.nodes()) {
            String[] shape = SHAPES.get(node.kind());
            out.append("  ")
                    .append(node.id())
                    .append(shape[0])
                    .append(MermaidText.label(node.label()))
                    .append(shape[1])
                    .append(":::")
                    .append(node.kind())
                    .append('\n');
        }
        for (Edge edge : graph.edges()) {
            out.append("  ")
                    .append(edge.from())
                    .append(" -->|")
                    .append(edge.relation())
                    .append("| ")
                    .append(edge.to())
                    .append('\n');
        }

        return out.toString();
    }
}
