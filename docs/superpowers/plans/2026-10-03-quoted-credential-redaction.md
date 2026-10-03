# Quoted credential assignments in string leaves

## Safety contract

Close the reproduced default-redaction bypass for a named password assignment whose quoted value
contains spaces. Use only synthetic credentials, temporary SQLite databases and random loopback
HTTP ports. No existing capture, provider, deployment, hook configuration or live database changes.
Preserve custom-pattern replacement, disabled ingestion, existing credential-name classification,
the 50,000 UTF-16-unit scan cap, Unicode-safe clipping and the truncation marker.

## Evidence and plan

A scratch fixture compiled current recording sources and used the actual canonical SQLite store.
With default redaction enabled, `password="FAKE STRING LEAF SECRET"` survived event text,
`toolInput.stdout`, `toolOutput.stdout`, metadata and `redactForExport`; the durable hook removed it.
The Java assignment regex excludes whitespace in quoted values and requires eight value characters.

1. Reproduce through focused string tests and actual HTTP capture/storage/read tests before editing
   production behavior. Exercise the model-export boundary with its existing fake transport.
2. Replace only default assignment matching with a bounded key/value scanner. Support bare or
   matching quoted credential names, short values, quoted whitespace and escaped quotes; preserve
   surrounding evidence and treat an unclosed quoted credential conservatively to the scalar end.
3. Preserve custom/disabled contracts and export's independent conservative fallback for escaped
   quoted keys. Keep existing key classification and provider-token rules.
4. Run focused tests, related recording/export contracts and scoped pinned Java formatting. Record
   the exact verification and limits here; coordinator owns Git and publication.

## Verification

Baseline: the three focused classes ran 29 tests with 21 failures and zero errors. Both new
ordinary/idempotent HTTP cases failed on actual SQLite JSON columns retaining fake credentials;
both disabled/custom-ingestion export cases failed on the serialized fake-transport request.
The existing structured-key HTTP controls remained green. No real provider was called.

The scanner now occupies the original assignment-rule position: after complete private-key blocks
and AWS keys, before Bearer/provider patterns and the existing export fallback. Review reproduced
an ordering regression when assignments ran first: an assignment-like line inside a PEM block could
remove its footer before the opaque block rule ran. Both an unterminated quoted assignment and a
plausible `tokenAA=` body line now retain full-block masking in ingestion and export.

Final verification:

- `mvn -B -q -Dsba.judge.enabled=false test`: 696 total, 669 passed, zero failures/errors, 27 skips.
  Skips are the 23 opt-in PostgreSQL cases, two live-model evaluations, one Elasticsearch test and
  the unavailable-Finder-path case on a Mac that has Finder. No live external services were used.
- All 71 focused cases passed across `QuotedCredentialRedactionTest`, `RedactionServiceTest`,
  `StructuredRedactionTest`, `StructuredRedactionHttpTest` and `JevExportRedactionTest`.
- Ordinary and idempotent HTTP capture persisted sanitized text and nested string leaves in actual
  SQLite columns. Event reads returned sanitized data; idempotent replay retained the event ID.
- Fake model transport and telemetry were checked with ingestion disabled and with replacement
  custom rules; neither contained the synthetic credential values.
- Coverage includes quoted spaces/newlines, JSON quoted names, short and empty values, escaped
  quotes/backslashes, unclosed quotes, benign surrounding evidence, multiple assignments,
  already-redacted markers and attached suffixes, clipping boundaries, dense input and PEM order.
- Scoped pinned Palantir formatting and `spotless:check` passed for the four changed Java files;
  `git diff --check` passed. No frontend behavior changed; the real-use checks are HTTP and the
  existing export adapter's fake transport, not a screenshot or rendering smoke.

## Limits and handoff

This remains best-effort redaction for a small named-assignment grammar. It does not decode
arbitrarily escaped/encoded text, parse shell syntax or JSON container values, broaden credential
name recognition, or rewrite existing captures. The export fallback for escaped quoted credential
keys remains conservative and may remove the rest of a string leaf. Input retains its existing
50,000 UTF-16-unit clipping budget; replacing short values with markers can expand sanitized text.

Codex implementation lane is frozen for coordinator review and Git integration. Owned files are
`RedactionService.java`, the new `QuotedCredentialRedactionTest.java`, the existing
`StructuredRedactionHttpTest.java` and `JevExportRedactionTest.java`, `docs/operations.md`, and this
plan. No Git writes, publication, live data changes, provider calls or persistent service changes
were made. Temporary HTTP fixtures closed their contexts; no packaged-browser port was reserved.
Next action: coordinator reviews the six-file diff, integrates current main and owns commit/PR/merge.

