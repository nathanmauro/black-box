package dev.nathan.sbaagentic.project;

import java.util.List;

/** Public alias expansion boundary for query adapters in neighboring modules. */
public interface ProjectScopeOperations {

    List<String> scopesFor(String scope);
}
