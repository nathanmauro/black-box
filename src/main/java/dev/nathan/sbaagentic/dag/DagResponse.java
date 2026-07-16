package dev.nathan.sbaagentic.dag;

import java.util.List;

public record DagResponse(List<DagNode> nodes, List<DagEdge> edges) {
}
