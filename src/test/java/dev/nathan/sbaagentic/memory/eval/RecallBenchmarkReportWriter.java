package dev.nathan.sbaagentic.memory.eval;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import dev.nathan.sbaagentic.memory.eval.RecallBenchmarkHarness.QueryOutcome;
import dev.nathan.sbaagentic.memory.eval.RecallBenchmarkHarness.Report;
import dev.nathan.sbaagentic.memory.eval.RecallBenchmarkHarness.SuiteSummary;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;

/** Renders a benchmark report as machine-readable JSON and a human-readable Markdown summary. */
final class RecallBenchmarkReportWriter {

    private static final ObjectMapper JSON = new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);

    private RecallBenchmarkReportWriter() {}

    static Path write(Report report, Path directory) {
        try {
            Files.createDirectories(directory);
            JSON.writeValue(directory.resolve("recall-benchmark.json").toFile(), report);
            Path markdown = directory.resolve("recall-benchmark.md");
            Files.writeString(markdown, markdown(report));

            return markdown;
        } catch (IOException ex) {
            throw new UncheckedIOException("could not write the recall benchmark report to " + directory, ex);
        }
    }

    static String markdown(Report report) {
        StringBuilder out = new StringBuilder();
        out.append("# Black Box recall quality benchmark\n\n");
        out.append(String.format(
                Locale.ROOT,
                "Corpus `%s`: %d projects, %d intent events, %d braids. %d measured iteration(s) per query.%n%n",
                report.corpusVersion(),
                report.projects(),
                report.intentEvents(),
                report.braids(),
                report.iterations()));
        out.append("| Suite | Queries | P@1 | P@5 | R@10 | MRR | NDCG@10 | Zero-result | Leaked |"
                + " Unstable | Modes | p50 ms | p90 ms | p99 ms | max ms |\n");
        out.append("|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|---|---:|---:|---:|---:|\n");
        for (SuiteSummary suite : report.suites().values()) {
            out.append(String.format(
                    Locale.ROOT,
                    "| %s | %d | %.3f | %.3f | %.3f | %.3f | %.3f | %.2f | %d | %d | %s | %.2f | %.2f | %.2f | %.2f"
                            + " |%n",
                    suite.suite(),
                    suite.queries(),
                    suite.precisionAt1(),
                    suite.precisionAt5(),
                    suite.recallAt10(),
                    suite.mrr(),
                    suite.ndcgAt10(),
                    suite.zeroResultRate(),
                    suite.leakedItems(),
                    suite.unstableQueries(),
                    suite.modes(),
                    suite.latencyP50Ms(),
                    suite.latencyP90Ms(),
                    suite.latencyP99Ms(),
                    suite.latencyMaxMs()));
        }
        out.append("\n## Weakest queries (NDCG@10 below 0.5, lexical ablation omitted)\n\n");
        boolean any = false;
        for (QueryOutcome outcome : report.queries()) {
            // The lexical ablation misses paraphrases by design; listing them buries real regressions.
            if (outcome.ndcgAt10() < 0.5 && !outcome.suite().endsWith(".lexical")) {
                any = true;
                out.append(String.format(
                        Locale.ROOT,
                        "- `%s` %s: NDCG@10 %.3f, RR %.3f, %d returned - \"%s\"%n",
                        outcome.suite(),
                        outcome.id(),
                        outcome.ndcgAt10(),
                        outcome.reciprocalRank(),
                        outcome.rankedKeys().size(),
                        outcome.query()));
            }
        }
        if (!any) {
            out.append("None.\n");
        }

        return out.toString();
    }
}
