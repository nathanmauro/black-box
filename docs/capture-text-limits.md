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
