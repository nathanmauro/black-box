# SQLite snapshot destination safety (NAT-314)

## Contract

A SQLite snapshot must not be published under its source database's `-journal`,
`-wal`, or `-shm` name. SQLite owns those paths and later source activity can delete
or overwrite them. Reject the destination with `sqlite_sidecar_destination` before
staging, opening SQLite, or invoking a native tool, whether the sidecar exists or
is currently absent. Reserve case variants conservatively, including on
case-sensitive filesystems; a real case-insensitive fixture reproduces the same
loss with an uppercase `-JOURNAL` destination. Also reserve canonically equivalent
Unicode basename spellings with NFC normalization and casefold; do not apply
compatibility normalization. A fresh review reproduced NFC/NFD aliases of the same
sidecar after the initial casefold-only guard.

Reject obvious normalized sidecar names during argument-only planning, with no
filesystem inspection, database access, output creation, or native tools without
`--execute`. Preserve `source_is_output`. At execution, compare source and output
parent directory identities so a source-parent symlink cannot bypass the guard. Do not resolve or follow a source leaf
symlink; the existing source-file validation remains in force. Directories must
remain trusted and stable during execution; this does not add protection against
adversarial concurrent directory replacement.

Only the backup CLI, its fixture tests, recovery documentation, and this plan are
in scope. PostgreSQL behavior, restore operations, live data, configuration, and
provider/network access are unchanged.

## Verification

1. Reproduce the actual CLI defect on a disposable DELETE-mode source, including a
   source-parent alias. Confirm the reported artifact disappears after a normal
   subsequent source commit.
2. Add actual CLI regressions for all three reserved names, absent/existing
   sidecars, normalized paths, and source-parent aliases. Check unchanged source
   and sidecar bytes and absence of staging residue.
3. Prove the execution guard precedes staging/SQLite/native-tool calls. Preserve
   no-I/O dry runs and source leaf symlink rejection.
4. Verify an ordinary standalone snapshot retains its exact bytes/hash and
   captured rows after later source writes, including WAL operation.
5. Run the complete Python storage safety suite on Python 3.9, inspect the scoped
   diff, and freeze the four owned paths for coordinator review/publication.

## Evidence

The baseline actual CLI reported successful 8,192-byte snapshots at `source.db-journal`
for both a direct source and a source-parent symlink. Each artifact existed after the
success response and disappeared after the next ordinary source commit; source row
count advanced from one to two. An uppercase `source.db-JOURNAL` reproduction showed
the same loss on the fixture's case-insensitive filesystem.

Before the fix, the expanded Python 3.9.6 suite ran 29 tests with 16 failed assertions
and one error on the missing execution-guard path. Six absent reserved destinations
incorrectly reported success; existing sidecars produced the generic collision error.
The remaining original checks and ordinary-snapshot controls passed. After the first
fix, all 29 passed; adding the case-variant CLI regression then produced three expected
failed subcases before the conservative basename correction.

Initial verification: all 30 storage tests passed on Python 3.9.6. These include the
12 direct/parent-alias × absent/existing × reserved-name combinations, all three
uppercase sidecar variants, no-filesystem-I/O planning assertions, an alias guard
that runs before staging/source-file inspection/native calls and closes its directory
fd, existing source-leaf/output symlink controls, and unchanged snapshot bytes,
SHA-256, row IDs, and BLOB values after later DELETE/WAL source commits. A sidecar-like
filename in a distinct output directory remains valid. The existing fake PostgreSQL
subprocess and archive-failure checks also pass; no real PostgreSQL run is claimed.

`git diff --check` passed. Temporary fixtures and subprocesses were cleaned by their
own test lifecycles. No live database, service, provider, configuration, or network
was used. This is a bounded destination guard, not protection against adversarial
concurrent directory replacement or against other programs using the chosen backup
name. Coordinator owns final review, Git, publication, and Linear reconciliation.

### Canonical Unicode follow-up

Before correction, four actual CLI fixtures (NFC to NFD and NFD to NFC, each with
and without a source-parent alias) reported completed `-JOURNAL` snapshots that the
next ordinary source commit removed. Each source retained both captured rows.
The identical new regressions failed before correction: 32 tests ran with 18
failed subcases (12 actual CLI cases and six no-I/O planning cases). The basename
key now applies canonical NFC normalization, casefold, and NFC normalization again.
Both the lexical planner and execution guard use the same key; parent identity
checks and the source-leaf-symlink policy remain unchanged.

Final verification: all 32 storage tests pass on Python 3.9.6, with no skips. The
new cases cover both NFC/NFD directions, direct and aliased parents, all three
uppercase sidecar suffixes, unchanged source bytes, absent artifacts/staging, and
no filesystem/tool I/O while planning. A compatibility-distinct `①.db` versus
`1.db-journal` control remains a valid plan. Python 3.9 syntax parsing and
`git diff --check` pass. All fixture trees/processes were cleaned; no live state,
PostgreSQL, network, providers, Git changes, or publication were involved.

## Coordinator acceptance

Fresh review reproduced a canonical Unicode filename alias bypass in the initial case-only guard.
The corrected guard normalizes canonical spelling before and after casefold, and its expanded
fixtures cover both normalization directions, source-parent aliases and uppercase sidecar suffixes.
Fresh review accepted the correction. Root independently passed all 32 storage tests on Python 3.9,
including actual CLI failure/preservation paths, and verified the four owned paths before integration.
No installed service, database, provider, configuration or restore operation changed.
