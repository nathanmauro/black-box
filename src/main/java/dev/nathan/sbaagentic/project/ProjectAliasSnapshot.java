package dev.nathan.sbaagentic.project;

import java.util.List;

/** Immutable one-query view of the alias graph for consistent project grouping. */
public interface ProjectAliasSnapshot {

    String resolve(String scope);

    List<String> scopesFor(String scope);

    List<ProjectScope> projectScopesFor(String scope);
}
