# Frozen SQLite snapshot to history text corpus (NAT-325)

## Contract

Bridge one explicitly selected, hash-pinned standalone Black Box SQLite snapshot into the existing
`blackbox.history-corpus/v1` manifest and `blackbox.history-items/v1` items file, so the qualified
literal and compact canonical adapters can consume it. Offline, Python 3.9 standard library, no
credentials, network, providers, hooks, services, live databases or history reads. Development
inventory history stays `not_staged` until a separately registered build exists.

Non-goals: no new corpus fields, no looser parser bounds, no alias/worktree normalization, no
tool/metadata payload export, no availability or efficacy claim, no runner wiring.

A snapshot hash identifies bytes; it never proves an event existed at the cutoff. The schema has no
`recorded_at` column, so items omit it and the build reports `availability: "unverified"`.

## Inputs

`history_corpus_build.py` takes `--snapshot`, `--snapshot-sha256`, `--corpus-id`, `--project`,
one or more `--session` (internal `agent_sessions.id`, never `client_session_id`), `--cutoff`
(RFC 3339, 1-9 fractional digits, `Z` or offset, compared as exact integer nanoseconds),
`--exclusions` (JSON file: list of `{"id","reason"}`, may be empty) and `--output` (a directory
that must not exist). Dry run is the default and prints a syntactic plan only: no SQLite module
connection, no snapshot read, no output. `--execute` materializes.

## Mapping decisions

| Corpus field | Source |
| --- | --- |
| `id` | `agent_events.id` verbatim; must match the corpus ID pattern or the build fails |
| `kind` | exact `event_type`: `Handoff`, `Decision`, `Observation` to `handoff`, `decision`, `observation`; everything else (messages, tools, `Idea`, `Evidence`, `Projection`) to `event` |
| `project` | the declared `--project` label (only matching rows are included) |
| `session` | internal `agent_sessions.id` |
| `observed_at` | stored text verbatim; nanoseconds preserved |
| `source_ref` | `blackbox-sqlite:<snapshot sha256>:agent_events:<event id>` |
| `text` | `agent_events.text` exactly (empty, whitespace, Unicode and NUL preserved) |
| `recorded_at` | omitted |

`message` is not produced: no single stored message event type is established. Omitted and
disclosed: `role`, `turn_id`, `tool_name`, `tool_input_json`, `tool_output_json`, `metadata_json`
(only `repo` is read, for attribution), `human_text`, `source`, `client_session_id`, all session
columns except `id`/`source`/`client_session_id`/`cwd`, and every other table.

Attribution: `metadata.repo` when it is a string that is not Java-blank (`String.isBlank`, matching
the server's `firstNonBlank`), otherwise the snapshot's mutable `agent_sessions.cwd`. Compared to
`--project` byte-for-byte. The cwd fallback is current session state, not historical proof.

## Validation order

1. Arguments: hash format, corpus ID, project/session/cutoff syntax, unique allowlist, exclusions
   file (bounded, exact keys, unique IDs, non-empty reasons).
2. Output parent opened without following symlinks; output name absent.
3. Source: parent and leaf opened no-follow, regular non-empty file within the size bound, no
   `-wal`/`-shm`/`-journal` sidecar.
4. Raw stream copy into a private 0700 staging directory while hashing; source identity, size,
   mtime/ctime unchanged across the copy; hash equals `--snapshot-sha256`; sidecars still absent.
5. Copy header: SQLite magic and rollback-journal (non-WAL) format bytes.
6. Open the private copy only, `mode=ro&immutable=1`, `trusted_schema=OFF`, `query_only=ON`,
   runtime deadline. UTF-8 encoding, `quick_check`, `agent_sessions`/`agent_events` are ordinary
   tables with the required columns. Views, virtual tables and missing columns are unsupported.
7. Every allowlisted session exists exactly once with TEXT identity columns.
8. Events of allowlisted sessions in ID order: identity agrees with the session
   (`source`, `client_session_id`) -> TEXT `observed_at` parses -> late rows filtered and counted ->
   metadata parses (object, unique keys, finite, bounded) and `repo` is a string/null/absent ->
   other-project and unattributed rows filtered and counted -> declared exclusions removed ->
   event ID syntax -> NULL text fails unless excluded -> TEXT type, strict UTF-8, 64 KiB bound.
9. Every exclusion matched an in-scope event, otherwise the build fails.
10. Item count, items-file and manifest byte limits, then a `load_corpus` self-check on staged files.
11. Publish: `mkdir` the output exclusively (0700), link items, then link the manifest last as the
    commit marker. Any failure removes only our own inode-checked entries; foreign files are kept.

Errors are stable codes in `{"status":"failed","error":code}`; no text, paths or exceptions.

## Verification

Synthetic snapshots are created from `src/main/resources/schema.sql` and taken through the real
`scripts/storage/blackbox_backup.py` CLI. Tests cover dry run without SQLite, CLI execute plus
`load_corpus` and literal `validate`/`serve`, compact backend contract with the existing fakes,
byte-identical rebuilds, scope collisions, source/client duplicates, repo override/fallback, cutoff
offsets and nanoseconds, exclusions, NULL versus empty text, malformed schema/data, hash mismatch,
mid-copy mutation, sidecars, WAL, unsafe output, no overwrite, failure cleanup and exact limits.
Then the full Python 3.9 benchmark suite and `git diff --check`.

## Observed results

- `test_history_corpus_build.py`: 23 behavioral tests pass on Python 3.9.6. Every snapshot passes
  through the real backup CLI; the builder runs in-process and as a CLI; output is checked with
  `load_corpus`, the literal `validate`/`serve` CLI, and the compact backend over the existing fake
  canonical API.
- Mutation checks on a scratch copy all failed the suite:
  - unmatched exclusions accepted;
  - session identity check removed;
  - WAL header check removed;
  - both source-stability guards removed;
  - label trailing-slash normalization;
  - NULL text turned into empty text;
  - exclusive cutoff;
  - no output cleanup.
  Removing only one of the two redundant stability guards is still caught by the other.
- Apple's system SQLite can leave a persistent empty `-wal` after closing a WAL database. The
  sidecar check refuses it, and the WAL header check refuses the file even without it.
- A manual synthetic build (11 events, 3 sessions, one late, one other-project, one excluded)
  produced 8 items. A rebuild was byte-identical, and literal `validate`/`serve` accepted it.
- The packaged-JAR compact acceptance over that corpus was left to root and has not been run here.

## Review fixes (root-reproduced, builder 6d5239db)

1. **FIFO exclusions hang.** `_read_exclusions` now opens with `O_NONBLOCK | O_NOFOLLOW` and
   checks `fstat` before any read. FIFOs, devices and symlinks fail as `invalid_exclusions`.
   Regression: a real CLI dry run against a FIFO with no writer, with a 20 s subprocess timeout,
   plus a symlink rejection and a valid two-entry exclusions dry run.
2. **Staging collision deleted a foreign directory.** A `Staging` owner records the directory's
   identity only after its exclusive `mkdir` succeeds. It removes only the fixed files it created
   (snapshot copy, items, manifest), each identity-checked, then `rmdir`s its own directory. There
   is no recursive delete. A collision is `staging_collision`. Regression: fixed `token_hex`, a
   pre-created directory with a sentinel; the sentinel survives and no output appears.
3. **Deadline ended at selection.** `check_deadline` now also runs after `render`, after the
   `load_corpus` self-check, before the output `mkdir`, and before the manifest link. Expiry rolls
   back publication. Regressions: a fake monotonic clock jumps 1000 s after `render`, after
   `load_corpus`, and between the items and manifest links. Each gives `build_timeout` with no
   output and no staging left.
4. **Cleanup failure reported as complete.** The private snapshot copy is unlinked and its
   absence verified before publication. Any cleanup failure exits 1 with `cleanup_failed` plus
   `published` and `staging_removed` booleans, and the earlier failure's stable code as `cause`
   when there was one. Classification:

   | Injected failure | `published` | `staging_removed` | Residue |
   | --- | --- | --- | --- |
   | Snapshot-copy unlink, EIO every time | false | false | staging holds only `snapshot.sqlite` |
   | Snapshot-copy unlink, EIO first time only | false | true | none |
   | Hash mismatch, then snapshot-copy unlink EIO | false | false | `cause: snapshot_hash_mismatch` |
   | Staged `items.json` unlink EIO after publish | true | false | staging holds only `items.json` |
   | Staging `rmdir` EACCES after publish | true | false | empty staging directory |

   In the published cases the output corpus still passes `load_corpus`. Failures are injected by
   patching `os.unlink`/`os.rmdir` for one fixed staged name: no filesystem races, no real data.

All seven new regressions fail against builder `6d5239db`, including the FIFO subprocess timing
out at 20 s, and pass after the fix. The manual synthetic acceptance corpus rebuilt byte-identically
(manifest `377d3d35…`, items `8f3d5139…`), so mapping and positive output are unchanged.

Limitation: the staging and output parent remains caller-trusted. Identity checks catch replaced
entries but cannot stop an attacker who can already write that directory, and SQLite still opens
the staged copy by path.

## Review fix: source sidecar aliases

Root reproduced this on macOS: an output named `source.db-WAL` beside `source.db` completed and
created a real `source.db-wal` directory on the case-insensitive filesystem. The plan check only
compared names exactly. The builder now applies the `blackbox_backup.py` naming contract:

- names are compared after canonical NFC normalization plus casefold (no NFKC compatibility
  folding);
- the snapshot's own name and its `-wal`, `-shm` and `-journal` names are reserved;
- `make_plan` refuses them when both parents are lexically the same;
- `execute` refuses them right after opening the output parent, when the snapshot parent's
  `stat` identity equals that fd's. This catches differently spelled aliases before staging or
  output is created.

Errors are `output_is_snapshot` and `output_is_sidecar`; storage backup code is unchanged.

Regressions (`SidecarAliasTests`):

- An actual CLI build with `-WAL`, `-Shm` and `-JOURNAL` outputs is refused. No reserved path
  appears, no staging is left, and the source bytes are unchanged.
- Plan cases: case variants, NFC/NFD equivalents, `ß`/`SS` casefold, and the snapshot's own name.
  Controls that still plan: fullwidth compatibility-distinct names, `-wal2` and `.wal` names.
- Portable identity unit: a `data/x/..` spelling of the same parent is refused, while a distinct
  directory and a similar name are allowed.
- Filesystem-dependent: on a case-insensitive filesystem, a `DATA/` alias of a `Data/` parent is
  refused by identity. On a case-sensitive one the alias does not exist, so the case expects
  `unsafe_output_parent`. A different-directory control build completes.

The `-WAL` case run against builder `6d5239db` completed and created `source.db-wal`. The fixed
builder fails with `output_is_sidecar` and creates nothing.

Also corrected the protocol: the builder adds no build-time timestamps or snapshot, staging or
output paths. Source `observed_at`, the cutoff and declared labels, including project paths, are
kept verbatim.

## Review fix: honest publication rollback

Root reproduced this against builder `bc03ae43`, and an independent reviewer agreed:

- EIO was injected on the output directory's `fsync`, then on rollback `unlink` in the output
  directory only.
- The builder exited 1 with only `build_failed`, while a valid manifest and items were still
  published.
- `publish` had swallowed rollback `OSError`s.

`rollback()` now reports its real outcome:

- It returns nothing only when none of the builder's identity-checked links remain and its output
  directory is gone, or is kept only because of foreign entries (`ENOTEMPTY`).
- Otherwise `publish` raises `cleanup_failed` with the original `cause` and these fields:
  - `published`: both links remain;
  - `output_removed`;
  - `output_entries`: the remaining fixed names.
- `execute` then still removes staging, keeps those fields and adds `staging_removed`.

Regressions (`PublicationRollbackTests`; EIO injected only on calls that target the output
directory):

| Injected | Result |
| --- | --- |
| Output `fsync` only (control) | `build_failed`, no output, no staging |
| Output `fsync` and output-dir `unlink` | `cleanup_failed`, `cause: build_failed`, `published: true`, `staging_removed: true`, `output_removed: false`, entries items+manifest; the corpus loads |
| Output `fsync` and manifest `unlink` only | `published: false`, entries `[manifest.json]` |
| Output `fsync` and output `rmdir` | `published: false`, `output_removed: false`, entries `[]` |

A mutant whose rollback always reports success fails all three non-control cases. The existing
foreign-file, interrupt and deadline rollback tests still pass unchanged. Abrupt process death
(SIGKILL, power loss) is outside the cleanup guarantee and is documented as such in the protocol.

## Independent integrated acceptance

The final builder and tests were unchanged when integrated with main after PR #122. An independent
review accepted the sidecar-alias and publication-rollback corrections. The combined Python 3.9.6
suite passed **239 tests**, including 37 builder tests.

Root created separate synthetic snapshots through the real backup CLI and checked the complete
13-item expected projection, a byte-identical rebuild, and a 12-item projection with one additional
explicit exclusion. Dry-run, no-overwrite and six invalid-input cases passed, with no unexpected
output or staging. Literal validate/serve returned the expected six searches (6,310 delivered bytes).

Both corpora then passed twice through fresh packaged Black Box servers: 50 exact database rows,
24 canonical pages, 28 deliveries and 26,576 delivered bytes. Repeated delivery output was
byte-identical. Text, metadata, nanosecond timestamps, Unicode, NUL, blank-text handling and the
synthetic credential-looking text were checked. The private adapter intentionally disables ingestion
redaction; the fake credential-looking item is preserved, and only its declared exclusion removes it.
All owned processes and temporary storage were cleaned up; source artifacts and the installed
listener were unchanged.

The first private harness attempt was inconclusive because the macOS Python launcher created an
xcrun cache in the test temporary directory. The harness now invokes the verified Python 3.9.6
binary directly without weakening cleanup assertions. A sandboxed full-suite attempt could not
start loopback test servers; the authorized isolated rerun passed. Neither required product changes.

See the machine-neutral [qualification report](../../evaluation-results/2026-10-03-frozen-corpus-builder-qualification.json)
for source, fixture, runner and packaged-JAR hashes. No authentic history was staged and no model
comparison or NAT-7 gate was cleared.
