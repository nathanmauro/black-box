package dev.nathan.sbaagentic.memory;

import java.util.List;

public interface MemoryRecallOperations {

    RecallResult recall(String scope, int withinHours, List<String> kinds);
}
