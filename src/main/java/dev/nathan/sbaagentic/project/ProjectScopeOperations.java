package dev.nathan.sbaagentic.project;

import dev.nathan.sbaagentic.recording.ProjectScopeResolver;
import java.util.List;

/** Public alias expansion boundary for query adapters in neighboring modules. */
public interface ProjectScopeOperations extends ProjectScopeResolver {

    String resolve(String scope);

    List<String> scopesFor(String scope);

    List<ProjectScope> projectScopesFor(String scope);

    ProjectAliasSnapshot snapshot();
}
