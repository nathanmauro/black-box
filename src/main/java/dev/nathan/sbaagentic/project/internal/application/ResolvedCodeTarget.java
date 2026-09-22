package dev.nathan.sbaagentic.project.internal.application;

import java.nio.file.Path;

record ResolvedCodeTarget(Path scopeRoot, Path workspaceRoot, Path target, Integer line, Integer column) {}
