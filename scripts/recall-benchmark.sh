#!/usr/bin/env bash
#
# recall-benchmark.sh - run the offline recall quality benchmark and print its report.
#
# Seeds a synthetic multi-project corpus into a throwaway SQLite file under the JVM temp dir,
# runs recall, compact search, and braid discovery through the real application stack with a
# deterministic hashing embedder, and reports precision@k, MRR, NDCG@10, and latency
# percentiles. It never reads the live database, binds no port, and makes no network call.
#
# Usage:
#   scripts/recall-benchmark.sh [--iterations N] [--report-dir DIR]
#
# Writes DIR/recall-benchmark.json and DIR/recall-benchmark.md (default target/recall-benchmark)
# and exits non-zero when a quality floor in RecallQualityBenchmarkTest regresses.

set -euo pipefail

repo_dir="$(cd "$(dirname "$0")/.." && pwd)"
iterations=5
report_dir="$repo_dir/target/recall-benchmark"

while [[ $# -gt 0 ]]; do
    case "$1" in
    --iterations)
        iterations="${2:?--iterations needs a value}"
        shift 2
        ;;
    --report-dir)
        report_dir="${2:?--report-dir needs a value}"
        shift 2
        ;;
    -h | --help)
        sed -n '2,14p' "$0" | sed 's/^# \{0,1\}//'
        exit 0
        ;;
    *)
        echo "unknown argument: $1" >&2
        exit 2
        ;;
    esac
done

if ! [[ "$iterations" =~ ^[1-9][0-9]*$ ]]; then
    echo "--iterations must be a positive integer" >&2
    exit 2
fi

case "$report_dir" in
/*) ;;
*) report_dir="$PWD/$report_dir" ;;
esac

mkdir -p "$report_dir"
cd "$repo_dir"
status=0
mvn -q test \
    -Dtest=RecallQualityBenchmarkTest \
    -Dsurefire.failIfNoSpecifiedTests=false \
    -Dsba.bench.iterations="$iterations" \
    -Dsba.bench.report-dir="$report_dir" >"$report_dir.log" 2>&1 || status=$?

if [[ -f "$report_dir/recall-benchmark.md" ]]; then
    cat "$report_dir/recall-benchmark.md"
    echo
    echo "JSON: $report_dir/recall-benchmark.json"
fi
if [[ $status -ne 0 ]]; then
    echo "benchmark failed (exit $status); Maven output: $report_dir.log" >&2
    grep -E "AssertionFailedError|expected|but was|\[ERROR\]" "$report_dir.log" | head -20 >&2 || true
fi
exit "$status"
