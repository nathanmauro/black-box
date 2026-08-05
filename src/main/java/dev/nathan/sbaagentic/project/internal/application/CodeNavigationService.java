package dev.nathan.sbaagentic.project.internal.application;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.List;

import dev.nathan.sbaagentic.project.CodeNavigationOperations;
import dev.nathan.sbaagentic.project.CodeNavigationResult;
import dev.nathan.sbaagentic.project.CodeProjectScope;
import dev.nathan.sbaagentic.project.CodeReference;
import dev.nathan.sbaagentic.project.EditorProperties;
import dev.nathan.sbaagentic.project.internal.application.port.CommandLaunchException;
import dev.nathan.sbaagentic.project.internal.application.port.CommandLauncher;

import org.springframework.stereotype.Service;

@Service
public class CodeNavigationService implements CodeNavigationOperations {

    private static final String FINDER_COMMAND = "/usr/bin/open";

    private final CodeScopeResolver resolver;
    private final CommandLauncher launcher;
    private final EditorProperties properties;

    public CodeNavigationService(
            CodeScopeResolver resolver,
            CommandLauncher launcher,
            EditorProperties properties) {
        this.resolver = resolver;
        this.launcher = launcher;
        this.properties = properties;
    }

    @Override
    public List<CodeProjectScope> codeScopes() {
        return resolver.codeScopes();
    }

    @Override
    public CodeNavigationResult openInEditor(CodeReference reference) {
        Path editor = verifiedEditorCommand();
        ResolvedCodeTarget resolved = resolver.resolve(reference);
        try {
            launcher.launch(
                    List.of(editor.toString(), resolved.workspaceRoot().toString()),
                    properties.getTimeout());
            launcher.launch(
                    List.of(editor.toString(), "-g", gotoTarget(resolved)),
                    properties.getTimeout());
        }
        catch (CommandLaunchException ex) {
            throw failure(
                    CodeNavigationError.EDITOR_DISABLED,
                    "The configured editor could not be opened.",
                    ex);
        }
        return new CodeNavigationResult("opened");
    }

    @Override
    public CodeNavigationResult revealInFinder(CodeReference reference) {
        ResolvedCodeTarget resolved = resolver.resolve(reference);
        Path command = Path.of(FINDER_COMMAND);
        if (!Files.isRegularFile(command) || !Files.isExecutable(command)) {
            throw failure(
                    CodeNavigationError.REVEAL_UNAVAILABLE,
                    "Finder reveal is unavailable on this machine.");
        }
        try {
            launcher.launch(
                    List.of(FINDER_COMMAND, "-R", resolved.target().toString()),
                    properties.getTimeout());
        }
        catch (CommandLaunchException ex) {
            throw failure(
                    CodeNavigationError.REVEAL_UNAVAILABLE,
                    "Finder could not reveal the file.",
                    ex);
        }
        return new CodeNavigationResult("revealed");
    }

    private Path verifiedEditorCommand() {
        if (!properties.isEnabled()
                || properties.getCommand() == null
                || properties.getCommand().isBlank()) {
            throw editorDisabled();
        }
        try {
            Path configured = Path.of(properties.getCommand());
            if (!configured.isAbsolute()
                    || !Files.isRegularFile(configured)
                    || !Files.isExecutable(configured)) {
                throw editorDisabled();
            }
            Path realCommand = configured.toRealPath();
            boolean allowed = properties.getAllowlist() != null
                    && properties.getAllowlist().stream()
                            .map(CodeNavigationService::realPath)
                            .flatMap(java.util.Optional::stream)
                            .anyMatch(realCommand::equals);
            if (!allowed) {
                throw editorDisabled();
            }
            return realCommand;
        }
        catch (InvalidPathException | IOException | SecurityException ex) {
            throw failure(
                    CodeNavigationError.EDITOR_DISABLED,
                    "Open in editor is disabled or misconfigured.",
                    ex);
        }
    }

    private static java.util.Optional<Path> realPath(String value) {
        if (value == null || value.isBlank()) {
            return java.util.Optional.empty();
        }
        try {
            Path path = Path.of(value);
            if (!path.isAbsolute()) {
                return java.util.Optional.empty();
            }
            return java.util.Optional.of(path.toRealPath());
        }
        catch (InvalidPathException | IOException | SecurityException ex) {
            return java.util.Optional.empty();
        }
    }

    private static String gotoTarget(ResolvedCodeTarget target) {
        String value = target.target().toString();
        if (target.line() != null) {
            value += ":" + target.line();
            if (target.column() != null) {
                value += ":" + target.column();
            }
        }
        return value;
    }

    private static CodeNavigationException editorDisabled() {
        return failure(
                CodeNavigationError.EDITOR_DISABLED,
                "Open in editor is disabled or misconfigured.");
    }

    private static CodeNavigationException failure(CodeNavigationError code, String message) {
        return new CodeNavigationException(code, message);
    }

    private static CodeNavigationException failure(
            CodeNavigationError code,
            String message,
            Throwable cause) {
        return new CodeNavigationException(code, message, cause);
    }
}
