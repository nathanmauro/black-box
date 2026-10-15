# macOS quick recall

> **Status:** optional and macOS-only. Build it from source; the bundle is ad hoc signed for local
> use, not notarized. Its checks run locally and are not part of CI or `scripts/verify.sh`.

Black Box Recall is a small native menu bar helper. Click its box or press **Control–Option–B**,
type a project, topic, or remembered phrase. Up to five matching decisions and handoffs appear
in the dropdown with a short headline, project, and kind. Click a match to open its exact capture
in Chrome, or press **Return** / click **Open results** for the full search page.
**Escape** dismisses the popover. **Quit** stops the helper until you launch it again or log in.

The helper opens `/recall?scope=…&run=1` on the configured Black Box server. `run=1` runs that
recall once with the page's defaults (the last 168 hours; Decision and Handoff events), then drops
`run` from the address. `scope` is the Recall page's legacy parameter: it matches recorded paths
and text rather than one exact project, and the page labels it a legacy scope link; select a
project there for an exact filter. A match opens `/?view=browse&session=…&event=…` with remembered
project scope cleared, as the Recall page's own source links do. This helper stores no search
history and does not start, stop, or configure the Black Box server. A server must already be
available.

The [companion](companion.md)'s expanded recall field is the embedded alternative. It opens
`/recall?project=…&query=…&run=1` for the open project (or across projects from the river), without
a global shortcut, live matches, or Jev trace. The Recall page accepts both link forms; see
[project continuity](project-continuity.md).

Live matches call `GET /api/recall` with the same legacy `scope` and one-week Decision/Handoff
defaults, limited to five items. They start after two characters and a 350 ms typing pause; each
changed query cancels the previous request and discards late responses. Requests time out after
eight seconds. Ordinary previews use the existing recall ranking. Loading, no-match, and
connection errors appear inside the dropdown. Standard Command–A/C/X/V/Z editing shortcuts work
in the query field.

## Trace a remembered thread with Jev

**Trace with Jev** (Command–J) compares the thing you remember with evidence captured later. It first selects
a likely source from a bounded 30-day recall shortlist, then looks for a related later capture in
the same repository. The dropdown shows **You may remember this** and, when supported,
**Captured later**. Both open the original event in Chrome.

Relationship labels describe the source: a later decision differs, a capture reports completion,
or a capture reports a blocker. They are advisory judgments over retrieved excerpts, not proof of
the current state of a project. A missing connection means none was established in the bounded
candidate set. It does not mean nothing happened later. Timestamps describe when evidence was
captured; an old event can be described in a newer capture.

Tracing is an explicit action. Ordinary typing does not call Jev. A trace sends the query and
bounded Decision/Handoff excerpts, with each capture's kind, capture time, and repository folder
name, to TypeSafe's HTTPS API in at most two calls; it does not send whole transcripts or
continuously relay new events. Jev selects short candidate labels which the helper maps back to
local source links; excerpts can themselves contain source identifiers. The helper renders source
text and fixed labels, not generated summaries. Query changes and popup dismissal cancel the
pending trace; ordinary search remains available during errors or uncertainty.

The provider key belongs in the helper's macOS Keychain item. It is never stored in the application
bundle, repository, or login-agent plist. Jev setup is separate from basic search installation.
The implementation follows TypeSafe's [typed API](https://docs.typesafe.ai/api) and
[evidence reranking pattern](https://docs.typesafe.ai/cookbooks/rerank_typesafe).

It prefers an installed **Black Box** Chrome web app whose shortcut URL has the same origin as
the server. It discovers the app under `~/Applications/Chrome Apps.localized/` from its plist
and reads that app's registration in Chrome's local state to retain the original profile.
No Chrome app ID or machine-specific path is embedded. If no matching app/profile can be resolved
unambiguously, it opens a regular Chrome tab. An unavailable browser or launch error appears in the popover.
The helper launches Chrome with an app ID and explicit launch URL: opening the macOS web-app shim
with a URL can silently open the home page instead. Query text is passed as a discrete argument,
never evaluated by a shell. Chrome may open another app window depending on its launch behavior.
A successful app launch alone cannot establish that the server is reachable; Chrome displays
connection errors.

## Build and install

Requires macOS 13 or newer and Apple's Swift compiler (Xcode or Command Line Tools), with no
third-party packages or Xcode project. Build artifacts and the module cache stay under `target/macos/`.
Building the helper does not replace the server JAR in `target/`; `mvn clean` deletes this build
output but not an installed copy. The script ad hoc signs the bundle for local use; it is not a
notarized distribution.

```bash
./scripts/build-menubar.sh
./scripts/install-menubar.sh           # dry run; changes nothing
./scripts/install-menubar.sh --install # install and start at login
```

To enable Trace, first make `TYPESAFE_API_KEY` available in the installing shell through your
normal private credential setup, then run:

```bash
./scripts/install-menubar.sh --install --enable-jev
```

This configures the installed app's own Keychain item and saves only a nonsecret enable flag,
`BLACKBOX_JEV_ENABLED=1`, in its login agent; Trace stays unavailable without both. Never put the
key into this command line, the repository, or a support transcript.
Basic installs preserve an existing server origin and Jev enable flag. Ad hoc rebuilds may require
re-running the explicit enable step to restore Keychain access for the new binary. That setup
command can show macOS's approval prompt for the helper's own TypeSafe item; a refusal preserves
the existing item. Normal use suppresses Keychain prompts and reports unavailable access instead
of waiting for a security dialog.

Installation writes only `~/Applications/Black Box Recall.app` and
`~/Library/LaunchAgents/dev.blackbox.quick-recall.plist`, and loads that login agent.
Existing targets must have the helper's own identity before the installer replaces them;
symlink targets are rejected. A manually launched copy must be quit before reinstalling; the installer
refuses to overwrite a running copy outside its launch agent. It explicitly starts the installed
agent and checks for its process rather than assuming that loading the agent started it. The login agent
uses `RunAtLoad` without `KeepAlive`, so choosing Quit does not cause an immediate restart.

For a different HTTP or HTTPS origin, pass the optional configuration when installing:

```bash
BLACKBOX_URL='https://blackbox.example.com/' ./scripts/install-menubar.sh
BLACKBOX_URL='https://blackbox.example.com/' ./scripts/install-menubar.sh --install
```

The installer validates this URL before saving it in the login-agent plist. Credentials,
query parameters, fragments, and non-HTTP schemes are rejected. The helper always opens the root
`/recall` route. Browser authentication remains in Chrome. Configuration errors shown by a
manually launched helper can be corrected by relaunching with a valid `BLACKBOX_URL` environment.
The preview request does not copy Chrome cookies or credentials. A server requiring sign-in
can still be searched in Chrome. Local HTTP networking is allowed; use HTTPS for remote preview
servers because the helper retains macOS transport security for nonlocal connections.

The Carbon hotkey uses no Accessibility or Input Monitoring permission. If another app has already
registered Control–Option–B, the helper displays an error and instructions to free that shortcut
and relaunch; the menu bar button remains available. A duplicate helper launch activates the
existing instance instead of intentionally registering another shortcut.

## Verification

```bash
./scripts/test-menubar.sh
```

This checks URL round trips (Unicode, spaces, `+`, `&`, `#`, and percent characters), rejects unsafe
base URLs, checks Chrome app/profile selection and recall preview decoding/exact-event links,
compiles and signs the actual app, checks shell syntax, and exercises the installer dry run.
It also checks Jev response validation, source qualification, exact repository/date filtering,
mocked two-call transport and cancellation, plus installer configuration preservation and rollback.
The installer fixtures use `python3`, temporary targets, and inert `launchctl`/Keychain stand-ins.
It does not install or launch the GUI. The dry-run check reads, but never writes, the real install
targets under your home directory; set `HOME` to an empty temporary directory to isolate those
reads as well. For a real use check after installation:

1. Click the box and confirm the query field has focus; Escape should dismiss it.
2. From another app, press Control–Option–B and confirm the query field has focus.
3. Enter a real query, confirm up to five matches, and click one. Confirm the matching Black Box
   Chrome app (or Chrome fallback) opens the owning session at that exact event.
4. Reopen, paste `café + a&b#c`, and press Return. Confirm Recall preserves the query and loads
   results. Change queries quickly and verify old matches do not replace newer results.
5. Click Open results and confirm the same behavior. Choose Quit and confirm it stays stopped.
6. Relaunch the installed app, then launch it again and confirm only one menu bar helper remains.

The Jev evaluation command is deliberately separate and requires `--live` before any provider
call. Its bundled fixtures are synthetic:

```bash
./scripts/eval-jev-recall.sh --live --fixture fixtures/jev-recall-trace.json
```

It reports selected source IDs, typed judgments, distributions, model, token usage, call count,
and latency without printing the credential or source payloads. These small cases are regression
smokes; they do not establish global model accuracy. For a native UI check without sending real
history, run `python3 scripts/serve-jev-recall-fixture.py`, temporarily install with
`BLACKBOX_URL=http://127.0.0.1:18767/`, and trace “the app that dropped my search query”. That
trace still sends the synthetic excerpts to TypeSafe. Restore the normal server origin after that
check. The fixture server opens no database and serves only synthetic evidence.

## Uninstall

Quit the helper, then verify these are its own app and agent before removing these exact targets:

```bash
/usr/libexec/PlistBuddy -c 'Print :CFBundleIdentifier' "$HOME/Applications/Black Box Recall.app/Contents/Info.plist"
/usr/libexec/PlistBuddy -c 'Print :Label' "$HOME/Library/LaunchAgents/dev.blackbox.quick-recall.plist"
```

Both should report `dev.blackbox.quick-recall`. Then:

```bash
launchctl bootout "gui/$(id -u)" "$HOME/Library/LaunchAgents/dev.blackbox.quick-recall.plist"
rm "$HOME/Library/LaunchAgents/dev.blackbox.quick-recall.plist"
rm -rf "$HOME/Applications/Black Box Recall.app"
```

If the helper was already unloaded, `bootout` may report that the service was not found. These paths
belong only to the quick-recall helper; no server service is involved. If you enabled Jev, its
Keychain item remains; remove it separately when no longer needed:

```bash
security delete-generic-password -s dev.blackbox.quick-recall.typesafe -a typesafe
```

Implementation records: [quick recall](superpowers/plans/2026-10-02-macos-quick-recall.md) and
[Jev trace](superpowers/plans/2026-10-02-jev-recall-trace.md).
