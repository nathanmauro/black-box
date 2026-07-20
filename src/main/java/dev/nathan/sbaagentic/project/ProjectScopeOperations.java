package dev.nathan.sbaagentic.project;

import java.util.List;

import dev.nathan.sbaagentic.recording.ProjectScopeResolver;

/** Public alias expansion boundary for query adapters in neighboring modules. */
public interface ProjectScopeOperations extends ProjectScopeResolver {

    String resolve(String scope);

    List<String> scopesFor(String scope);

    List<ProjectScope> projectScopesFor(String scope);

    ProjectAliasSnapshot snapshot();
}
