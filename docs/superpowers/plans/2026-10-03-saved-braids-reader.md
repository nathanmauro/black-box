# Saved braids reader (NAT-304)

## Contract and scope

Add a read-only Saved braids workspace at `/projects?view=braids`, with durable selection through
`meld=<id>`. Keep the ordinary project catalog, alias routing, trajectory focus and saved project
melds unchanged. Braids remain unassigned artifacts, not synthetic projects or Recall captures.
No provider invocation, creation controls, deployment or migration is part of this UI slice.
The coordinator also authorized a narrow `reveal=session` direct Browse exception for source links;
it applies only to the requested route ID, preserves saved filters and ends on ordinary selection.

The list calls only `GET /api/melds?kind=braid&scope=unassigned&limit=20`, with the opaque `before`
cursor on later pages. Show loaded items, never interpret page `count` as a corpus total. Detail
uses `GET /api/melds/{id}` independently of the current page. Null ownership identifies the
backend's unassigned artifact contract; caller metadata is not verified classification or citation.
A project-owned direct ID is distinguished and links to its project instead.

Display the full saved synthesis as text, save time, caller-declared generator fields and ordered
source-session references. Source links use actual internal session IDs with no invented event or
selected-project scope. New braids retain source/cwd/client snapshots; legacy references can fall
back to current joined values. Other fields are current; deletion can leave unavailable values and
zero events. Metadata is lazy and bounded, with explicit truncation and a raw saved-artifact JSON
download. Large numbers may be rounded in the parsed preview; the download fetches response text
without a JSON parse/serialize roundtrip. Pagination failures retain existing items;
selection cancels/ignores stale detail responses. The workspace does not depend on the catalog API.

## Verification plan

1. Implement against explicit fixtures while the backend is independently verified. Test nullable
   wire fields, strict list parameters, direct selection, ordinary-project IDs, empty/404/retry,
   stale responses, pagination failures and safe full-body/metadata rendering.
2. Run focused component/API tests, frontend checks and the full frontend suite.
3. After the coordinator integrates the verified backend, use the isolated packaged runtime to
   seed two projects and ordered sources, save/read braids, paginate more than 20 artifacts, follow
   a source, reload and use Back at desktop, narrow portrait and landscape sizes. Exercise honest
   empty/error fixtures without changing live data. Inspect screenshots and regenerate assets
   through the normal build.
4. Record exact results and limitations below; coordinator owns Git, PR, CI and merge.

## Results

Implemented and verified after coordinator integration of backend commit `1b21bf40`.

- **742 frontend tests across 61 files passed**; **103 focused API/reader/Projects/Sessions tests
  passed**. Lint has zero errors and the same 68 pre-existing warnings. Formatting, TypeScript and
  `git diff --check` passed; no rule exemption was added.
- **14 packaged browser journeys passed together, zero retries**: five new braid journeys plus
  project-alias, six mobile Browse viewports and two human-only picker journeys. The package build
  regenerated frontend assets through the normal Maven frontend profile.
- Real HTTP fixtures create two projects and ordered source sessions, save 21 braids per viewport,
  read an artifact outside the first page, retry an injected page failure without losing rows,
  retain exact body text, follow source links, and use reload/Back. A machine-only Codex source
  opens despite My turns and an incompatible Claude filter; selecting another session restores
  ordinary filtered behavior. Missing direct source IDs use actual HTTP 404s without a fallback.
- Actual downloads equal the raw detail HTTP response and preserve `9007199254740993`. Before the
  correction, a component fixture reproduced rounding to `9007199254740992`; another reproduced
  the full-reader styling mismatch under global My turns. Both are covered by passing regressions.
- Synthetic API interception covers empty/error catalogs and list responses, detail 404/retry and
  an ordinary project-owned ID. Unit fixtures additionally cover stale responses, delayed A→B
  session selection, focus movement during loading, deleted-source nullable fields, and metadata
  truncation/download failures. No live data or model/provider calls were used.
- Inspected screenshots at 1440×900, 390×844 and 844×390. Browser checks cover horizontal bounds,
  keyboard chooser dismissal/reselection, exact query-based active state and visible synthesis.
  Visual review caught router links marking every query-only row active; explicit native anchors
  now retain SPA navigation with correct `aria-current`. A stricter landscape check caught router
  scroll-to-top overriding reader selection; the supported `noScroll` link option preserves the
  intentional reader focus/scroll. Both corrections passed the final combined journeys.
- The owned 8799 fixture and temporary database were cleaned up. Protected port 8766 kept the same
  listener; the harness could not discover its database identity, so no DB-identity claim is made.

Source-only handoff in `codex/saved-braids-reader`; the coordinator owns review, Git, PR, CI and
merge. No worker commit, publication, deployment, database migration or Constellate change.
The separate NAT-306 MCP tool addition will require its own retirement-test count update; this
reader slice deliberately leaves that expectation unchanged.
