import com.sun.source.tree.BlockTree;
import com.sun.source.tree.CaseTree;
import com.sun.source.tree.CompilationUnitTree;
import com.sun.source.tree.DoWhileLoopTree;
import com.sun.source.tree.EnhancedForLoopTree;
import com.sun.source.tree.ForLoopTree;
import com.sun.source.tree.IfTree;
import com.sun.source.tree.LabeledStatementTree;
import com.sun.source.tree.ReturnTree;
import com.sun.source.tree.StatementTree;
import com.sun.source.tree.Tree;
import com.sun.source.tree.WhileLoopTree;
import com.sun.source.util.JavacTask;
import com.sun.source.util.SourcePositions;
import com.sun.source.util.TreePathScanner;
import com.sun.source.util.Trees;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import javax.tools.Diagnostic;
import javax.tools.DiagnosticCollector;
import javax.tools.JavaFileObject;
import javax.tools.SimpleJavaFileObject;
import javax.tools.ToolProvider;

/** Spotless nativeCmd step: add whitespace at AST-verified return boundaries, after Palantir. */
class BlankBeforeReturn {
    public static void main(String[] args) throws Exception {
        String source = new String(System.in.readAllBytes(), StandardCharsets.UTF_8);
        System.out.print(format(source));
    }

    static String format(String source) throws Exception {
        var compiler = ToolProvider.getSystemJavaCompiler();
        if (compiler == null) {
            throw new IllegalStateException("A full JDK 21 is required for return spacing");
        }
        var diagnostics = new DiagnosticCollector<JavaFileObject>();
        var input = new SimpleJavaFileObject(URI.create("string:///FormattingInput.java"), JavaFileObject.Kind.SOURCE) {
            @Override
            public CharSequence getCharContent(boolean ignoreEncodingErrors) {

                return source;
            }
        };
        List<Edit> edits = new ArrayList<>();
        try (var files = compiler.getStandardFileManager(diagnostics, null, StandardCharsets.UTF_8)) {
            var task = (JavacTask) compiler.getTask(
                    null, files, diagnostics, List.of("-proc:none", "--release", "21"), null, List.of(input));
            var units = task.parse();
            if (diagnostics.getDiagnostics().stream().anyMatch(d -> d.getKind() == Diagnostic.Kind.ERROR)) {
                throw new IllegalArgumentException("Java could not be parsed; no return spacing was applied");
            }
            SourcePositions positions = Trees.instance(task).getSourcePositions();
            for (CompilationUnitTree unit : units) {
                new TreePathScanner<Void, Void>() {
                    @Override
                    public Void visitReturn(ReturnTree node, Void unused) {
                        int start = Math.toIntExact(positions.getStartPosition(unit, node));
                        Tree parent = getCurrentPath().getParentPath().getLeaf();
                        Boundary boundary = boundary(parent, node, unit, positions, source);
                        int anchor = leadingComments(source, boundary, start);
                        int line = lineStart(source, anchor);
                        if (source.substring(line, anchor).isBlank()) {
                            if (line > 0 && !previousLineIsBlank(source, line)) {
                                edits.add(new Edit(line, line, newline(source)));
                            }
                        } else {
                            // Palantir keeps simple unbraced `if (...) return ...` on one line.
                            // Split only at the AST-verified body/trivia boundary, never in a token.
                            int whitespace = anchor;
                            while (whitespace > line && Character.isWhitespace(source.charAt(whitespace - 1))) {
                                whitespace--;
                            }
                            int indentEnd = line;
                            while (indentEnd < anchor && Character.isWhitespace(source.charAt(indentEnd))) {
                                indentEnd++;
                            }
                            String indent = source.substring(line, indentEnd) + "    ";
                            edits.add(new Edit(whitespace, anchor, newline(source) + newline(source) + indent));
                        }

                        return super.visitReturn(node, unused);
                    }
                }.scan(unit, null);
            }
        }
        var result = new StringBuilder(source);
        edits.sort(Comparator.comparingInt(Edit::start).reversed());
        for (Edit edit : edits) {
            result.replace(edit.start(), edit.end(), edit.text());
        }

        return result.toString();
    }

    private record Edit(int start, int end, String text) {}

    private record Boundary(int start, boolean header, String punctuation, String keyword) {}

    private static Boundary boundary(
            Tree parent, ReturnTree node, CompilationUnitTree unit, SourcePositions positions, String source) {
        int start;
        List<? extends StatementTree> siblings = null;
        String punctuation = "";
        String keyword = "";
        if (parent instanceof BlockTree block) {
            start = Math.toIntExact(positions.getStartPosition(unit, block)) + 1;
            siblings = block.getStatements();
        } else if (parent instanceof CaseTree branch && branch.getStatements() != null) {
            start = branch.getLabels().stream()
                    .mapToInt(label -> Math.toIntExact(positions.getEndPosition(unit, label)))
                    .max()
                    .orElseThrow();
            siblings = branch.getStatements();
            punctuation = ":";
        } else if (parent instanceof IfTree conditional) {
            if (conditional.getThenStatement() == node) {
                start = Math.toIntExact(positions.getEndPosition(unit, conditional.getCondition()));
            } else {
                start = Math.toIntExact(positions.getEndPosition(unit, conditional.getThenStatement()));
                keyword = "else";
            }
        } else if (parent instanceof WhileLoopTree loop) {
            start = Math.toIntExact(positions.getEndPosition(unit, loop.getCondition()));
        } else if (parent instanceof EnhancedForLoopTree loop) {
            start = Math.toIntExact(positions.getEndPosition(unit, loop.getExpression()));
            punctuation = ")";
        } else if (parent instanceof ForLoopTree loop) {
            start = Math.toIntExact(positions.getStartPosition(unit, loop)) + 3;
            List<Tree> header = new ArrayList<>(loop.getInitializer());
            if (loop.getCondition() != null) header.add(loop.getCondition());
            header.addAll(loop.getUpdate());
            for (Tree part : header) {
                start = Math.max(start, Math.toIntExact(positions.getEndPosition(unit, part)));
            }
            punctuation = "(;)";
        } else if (parent instanceof DoWhileLoopTree) {
            start = Math.toIntExact(positions.getStartPosition(unit, parent)) + 2;
        } else if (parent instanceof LabeledStatementTree label) {
            start = Math.toIntExact(positions.getStartPosition(unit, parent))
                    + label.getLabel().length();
            punctuation = ":";
        } else {
            throw new IllegalArgumentException("Unsupported return parent; source left unchanged");
        }
        boolean first = true;
        if (siblings != null) {
            for (StatementTree sibling : siblings) {
                if (sibling == node) break;
                start = Math.toIntExact(positions.getEndPosition(unit, sibling));
                first = false;
                punctuation = "";
            }
        }

        return new Boundary(start, first, punctuation, keyword);
    }

    // Only scan trivia after AST-located code boundaries, plus a known control-header terminator.
    // There are no Java expressions or literals in these intervals. Unknown forms fail closed.
    private static int leadingComments(String source, Boundary boundary, int end) {
        if (source.substring(boundary.start(), end).contains("\\u")) {
            throw new IllegalArgumentException("Unicode-escaped return trivia is not supported");
        }
        List<int[]> comments = new ArrayList<>();
        int cursor = boundary.start();
        while (cursor < end) {
            if (Character.isWhitespace(source.charAt(cursor))
                    || boundary.punctuation().indexOf(source.charAt(cursor)) >= 0) {
                cursor++;
            } else if (!boundary.keyword().isEmpty() && source.startsWith(boundary.keyword(), cursor)) {
                cursor += boundary.keyword().length();
            } else if (source.startsWith("//", cursor)) {
                int stop = source.indexOf('\n', cursor);
                if (stop < 0 || stop > end) throw new IllegalArgumentException("Unexpected return trivia");
                comments.add(new int[] {cursor, stop});
                cursor = stop;
            } else if (source.startsWith("/*", cursor)) {
                int stop = source.indexOf("*/", cursor + 2);
                if (stop < 0 || stop + 2 > end) throw new IllegalArgumentException("Unexpected return trivia");
                comments.add(new int[] {cursor, stop + 2});
                cursor = stop + 2;
            } else {
                throw new IllegalArgumentException("Unsupported return trivia; source left unchanged");
            }
        }
        int anchor = end;
        int next = end;
        for (int index = comments.size() - 1; index >= 0; index--) {
            int[] comment = comments.get(index);
            String gap = source.substring(comment[1], next);
            if (gap.chars().filter(c -> c == '\n').count() > 1
                    || (!boundary.header()
                            && !source.substring(lineStart(source, comment[0]), comment[0])
                                    .isBlank())) {
                break;
            }
            anchor = comment[0];
            next = comment[0];
        }

        return anchor;
    }

    private static String newline(String source) {

        return source.contains("\r\n") ? "\r\n" : "\n";
    }

    private static int lineStart(String source, int position) {

        return source.lastIndexOf('\n', position - 1) + 1;
    }

    private static boolean previousLineIsBlank(String source, int position) {
        int end = position - 1;

        return source.substring(lineStart(source, end), end).isBlank();
    }
}
