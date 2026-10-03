package dev.nathan.sbaagentic.lineage;
/** Bounded session-lineage DAG projections. */
public interface DagOperations {
    DagResponse forSession(String sessionId);
}
