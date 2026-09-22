package dev.nathan.sbaagentic.project.internal.application.port;

import dev.nathan.sbaagentic.project.ProjectAlias;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface ProjectAliasStore {

    List<ProjectAlias> findAll();

    Optional<ProjectAlias> findByAliasKey(String aliasKey);

    ProjectAlias insert(String id, String aliasKey, String canonicalKey, String source, Instant createdAt);

    int delete(String aliasKey);

    List<String> distinctObservedScopes();
}
