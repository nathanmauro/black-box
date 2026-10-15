# Captured text limits

Canonical ingestion applies redaction and then the configured `sba.ingestion.max-text-length`
limit to event text (20,000 UTF-16 units by default). If the text exceeds that limit, the stored
prefix ends with `\n[truncated]`. The marker is additional to the prefix budget.

With redaction enabled, each string scalar is scanned up to 50,000 UTF-16 units, including nested
tool input, tool output and metadata values. Longer scalars receive the existing ` …[truncated]`
marker before redaction. Export redaction applies the same scan ceiling. Disabling ingestion
redaction or supplying custom patterns retains its existing meaning.

A cut never splits a valid UTF-16 surrogate pair, such as an emoji. When a pair straddles the
boundary, the retained prefix is one unit shorter and the whole character is omitted. Characters
entirely within the budget remain intact; exact-limit strings gain no truncation marker. This
preserves character boundaries without expanding the budgets or changing the stored markers.

These limits apply before persistence. Reading an event returns its accepted stored text; it cannot
recover content that ingestion already redacted or truncated. Existing stored captures are not
rewritten by this boundary correction.

The recorder sets no request size limit, but its JSON reader rejects any single string longer than
20,000,000 UTF-16 units with `400 malformed_json` (Jackson's default read constraint). Only a payload
or text over 20,000,000 bytes can hold such a string, so for those alone the capture hook
(`scripts/hooks/sba-agent-hook.sh`) clips every string in the event's text, tool input, tool output
and metadata to 50,000 code points plus the same ` …[truncated]` marker before sending. That keeps
at least the prefix ingestion would store: stored text is unchanged, and so are stored tool and
metadata strings while ingestion redaction is enabled. Smaller payloads are not clipped by the hook.
