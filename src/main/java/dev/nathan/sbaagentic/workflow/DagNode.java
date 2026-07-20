package dev.nathan.sbaagentic.workflow;

public record DagNode(String id, String type, String label, String status, String ref) {
}
