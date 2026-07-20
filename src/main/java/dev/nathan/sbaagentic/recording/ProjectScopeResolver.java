package dev.nathan.sbaagentic.recording;

import java.util.List;

/** Recording-owned query input port implemented by the project module. */
public interface ProjectScopeResolver {

    List<String> scopesFor(String scope);
}
