package dev.nathan.sbaagentic.project;

import java.util.List;

public interface CodeNavigationOperations {

    List<CodeProjectScope> codeScopes();

    CodeNavigationResult openInEditor(CodeReference reference);

    CodeNavigationResult revealInFinder(CodeReference reference);
}
