package dev.nathan.sbaagentic.workflow;

import java.util.List;

public record DagResponse(List<DagNode> nodes, List<DagEdge> edges) {
}
