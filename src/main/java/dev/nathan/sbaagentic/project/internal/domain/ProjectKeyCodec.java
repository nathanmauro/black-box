package dev.nathan.sbaagentic.project.internal.domain;

import dev.nathan.sbaagentic.project.ProjectKey;

/** Internal string adapter retained while SQL and HTTP boundaries migrate to {@link ProjectKey}. */
public final class ProjectKeyCodec {

    public static final String NO_PROJECT_KEY = ProjectKey.NO_PROJECT;
    public static final String NO_PROJECT_LABEL = ProjectKey.NO_PROJECT_LABEL;

    private ProjectKeyCodec() {
    }

    public static String canonicalize(String cwd) {
        return ProjectKey.of(cwd).value();
    }

    public static String encode(String canonicalKey) {
        return ProjectKey.of(canonicalKey).encoded();
    }

    public static String decode(String projectKey) {
        return ProjectKey.decode(projectKey).value();
    }

    public static String labelFor(String canonicalKey) {
        return ProjectKey.of(canonicalKey).label();
    }
}
