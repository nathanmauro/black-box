package dev.nathan.sbaagentic.lineage;

import java.util.List;

public record DagResponse(List<DagNode> nodes, List<DagEdge> edges) {}
