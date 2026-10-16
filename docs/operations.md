# Run it

Black Box runs as one Spring Boot process, with SQLite as the default canonical store. Capture,
session lineage, and lexical recall need neither a model nor Elasticsearch. An optional PostgreSQL
profile owns a separate database for shared clients; it does not synchronize local history.
Commands below assume the repository root unless a path is explicit. To download or build Black
Box for the first time, start with [Install Black Box](installation.md), which also lists what each
platform supports.

## Command-line help

Discover the JAR's commands without starting the application:

```bash
java -jar target/sba-agentic-0.2.0.jar --help
java -jar target/sba-agentic-0.2.0.jar ingest --help
java -jar target/sba-agentic-0.2.0.jar help search
```

Top-level `-h` and `help`, and a known command followed by `-h`, also print help and exit
successfully. Help is handled before Spring reads configuration, opens a database, starts HTTP,
contacts a provider, or reads stdin. Command-specific help lists that command's implemented options.
Unknown or retired leading commands remain errors even when followed by `--help`; `help unknown`
is also an error, with no application startup.

Help flags must be separate arguments. Values such as `--text=--help`, `--q=--help`, and the
positional query `search help` remain normal command data. Running without a command starts the
HTTP service. Running a command without a help flag performs its normal operation: `ingest` writes
an event, summary commands use the configured summary backend and persist results, and
`embeddings-backfill --apply` generates and writes missing embeddings. The help-only guarantee
does not make those operations read-only or disable their configured providers.

A normal command closes its application context and exits after its synchronous operation finishes.
A command failure still exits nonzero. Successful `ingest` confirms canonical capture; optional
background terminal summaries and judgments can be interrupted during shutdown. Run `summarize`
or `summarize-missing` explicitly when you need to wait for the summary operation and its stored
result. No-command HTTP service mode stays running until shutdown.

## CLI capture input

Use equals-form options, for example `ingest --session=review --text='Keep the existing schema'`.
Ingest rejects extra positional arguments, including a value separated from its option by a space.
A present capture option must have a value (`--name=value`); a bare `--session` or `--tool` is an
error instead of selecting a default. Source, session and type must be nonblank when supplied.
These argument errors fail before stdin is read or a capture is persisted. Omitted options retain
their defaults, optional fields may still be explicitly empty, and Spring configuration options
remain supported. Other commands keep their existing argument syntax.

`ingest --text='note'` uses that value without reading stdin. An explicit empty or whitespace-only
value is also authoritative; use `--text=''` for a metadata-only capture. A bare `--text` without a
value is an error.

Without `--text`, piped, redirected or headless stdin is read through EOF, so a slow producer can
finish sending the capture before it is acknowledged. Input must be valid UTF-8 and at most 1 MiB
(1,048,576 bytes). Oversized input, malformed UTF-8 or a read error fails before any event is saved;
empty EOF permits a no-text capture. Accepted input still follows the configured canonical
redaction and text-length limits, so the input byte cap does not promise that all text is retained.

An attached Java console is not read, preserving nonwaiting metadata-only capture. Java can report
no console when stdout is redirected even if stdin is still a terminal; that case waits for EOF
unless `--text` is supplied. A pipe that never closes likewise waits for its producer to finish.

## Run as a service

An optional macOS menu bar helper, [macOS quick recall](macos-quick-recall.md), searches an already
running server from a **Control–Option–B** shortcut. It installs its own app and login agent
(`dev.blackbox.quick-recall`), never this server service; its opt-in **Trace with Jev** action sends
bounded excerpts to TypeSafe ([Cortex judgment stage](cortex.md)) only when invoked. The
[companion](companion.md)'s recall field is the embedded alternative. Both open the Recall page with
`run=1`; the helper uses the legacy `scope` parameter, which the page still accepts.

### macOS launchd

The deployment script updates an **existing healthy installation**. For initial setup, render and install
[`scripts/black-box.plist.template`](../scripts/black-box.plist.template) with the actual Java,
JAR, database, log, and service-label settings, build the initial JAR while no service uses it,
bootstrap the service, and verify `/api/status`. For subsequent updates, use:

```bash
./scripts/deploy-local.sh
```

The script supports only an **unauthenticated loopback** installation: its readiness check is an
unauthenticated `GET /api/status` on `127.0.0.1`, `localhost`, or `::1`, and enabling
[authentication](authentication.md) protects that route. An already authenticated installation
fails the initial health preflight before any service replacement; a new candidate that fails
readiness triggers rollback. It requires Python 3.9+, an existing healthy installation, and an installed plist with an
absolute `WorkingDirectory` and an explicit `java -jar /absolute/path/to/application.jar` command.
`/usr/bin/env KEY=value ... java -jar ...` is also supported. Other wrappers, JVM/application
arguments, path aliases, and hard-linked JARs are rejected. Add `WorkingDirectory` when installing
the template; the deploy script preserves the installed plist bytes. It compares the loaded
program, arguments, working directory, and environment with that plist before deployment and
after restart, rejecting unpinned inherited application/JVM settings. External configuration
files and external commands referenced by the installation are not fingerprinted or restored.

For a reviewed build made in an isolated checkout, use its absolute canonical path:

```bash
./scripts/deploy-local.sh --prebuilt-jar /path/to/isolated-build/target/sba-agentic-0.2.0.jar
```

Prebuilt mode never runs Maven. Without `--prebuilt-jar`, deployment clean-builds the frontend and
JAR in this checkout, which must exactly match the installed `WorkingDirectory` and Maven output.
Tests are skipped unless `--with-tests` is passed. Build and review a candidate separately when the
installed checkout has unrelated dirty work.

Choose your own reverse-DNS label when installing the template, for example
`com.example.black-box`, and point the script at it. `SBA_LAUNCHD_LABEL` selects
`~/Library/LaunchAgents/<label>.plist` and must match that plist's `Label`; `SBA_LAUNCHD_PLIST`
selects a plist at any path. Without either, the script falls back to the maintainer's historical
label, `com.nathan.sba-agentic`, which may not match your installation. Only the current user's `gui/<uid>` domain is supported. `SBA_JAR_PATH` and
`SBA_PORT` are optional assertions and must agree with the plist. `SBA_STATUS_URL` must address
`/api/status` on that installed local port, with no credentials or redirects. The default is
`http://127.0.0.1:<installed-port>/api/status`. The readiness port must belong to the exact new
launchd PID; a healthy response from another process does not count.

Before stopping, deployment validates the Boot archives, checks existing process health, stages
a supplied prebuilt candidate, and saves a hashed copy of the previous JAR outside `target/`. It acquires an
exclusive installation lock and refuses to build or replace a JAR while the previous PID or any
other JAR consumer remains alive. Stop errors are fatal. Installation uses an atomic rename,
then bootstraps the unchanged plist and verifies the new PID, HTTP 200, and the canonical
`storage.events`/`storage.sessions` count fields. Optional model/index availability is not required.

Build, installation, bootstrap, readiness, and handled interruption failures trigger binary
rollback and a readiness check of the restored service. The command still returns failure after
a successful rollback. `SBA_DEPLOY_STOP_TIMEOUT` defaults to 20 seconds and
`SBA_DEPLOY_READY_TIMEOUT` to 60 seconds; each phase is bounded. Recovery files are retained in a
private `.blackbox-deploy/<installation-id>/recovery-*` directory beside the installed checkout.
The command prints its exact location; it includes `previous.jar`, the original plist bytes,
checksums/identity in `recovery.json`, and a build log when applicable. These files can contain
private installation configuration: keep them local and remove obsolete recovery directories
only after verifying the deployment.

If rollback is not verified, preserve that directory and inspect the reported service identity.
Stop the service and confirm its PID and all JAR consumers are gone before restoring
`previous.jar` to the recorded destination. Verify its SHA-256 against `recovery.json`, bootstrap
the unchanged installed plist, and check the new PID, port ownership, and `/api/status`. The script
does not kill unrelated consumers or rewrite a plist that changed during deployment. An
uncatchable termination or machine crash requires this manual recovery procedure; the OS releases
the advisory deployment lock automatically.

Binary rollback does **not** reverse database migrations or writes made by the candidate. Deploy
only backward-compatible schema changes when relying on binary rollback, and maintain a separate
verified database backup/recovery procedure. Additive capture-receipt tables, for example, remain
in the database after restoring an older binary. The script does not back up or restore databases.
Use the separate [database snapshot and restore rehearsal](database-recovery.md) to verify recovery
before relying on a backup or performing an irreversible schema change.

Do not replace the executable JAR underneath a running JVM. An ordinary `mvn package`, including
packaging performed for E2E tests, can overwrite `target/` while a service is using it. Use the
managed deploy sequence or build in an isolated checkout/output location. A live PID alone does
not establish health: check `/api/status`, the UI, and a representative API route after restart.

### Stopping and restarting

Stopping the service first ends every `/api/stream` subscription and every MCP listening stream
(`GET /mcp`), because those responses never finish on their own. They end cleanly, so browsers and
other `EventSource` clients reconnect with `Last-Event-ID` and resume after their last event (see
[durable stream recovery](durable-stream-recovery.md)); MCP clients start a new session. One kind is
not reached: an MCP listening stream resumed with `Last-Event-ID`, which the MCP SDK cannot replay,
stays open until the phase timeout below cuts it off. During shutdown a new `/api/stream` request
ends after its opening frames, and a new MCP request receives 503. Graceful shutdown then waits for
in-flight requests and their post-commit fan-out, such as Elasticsearch indexing, for at most
`spring.lifecycle.timeout-per-shutdown-phase` (2 seconds) per shutdown phase. That bound also ends
shutdown before browsers reconnect, 3 seconds after a stream ends; a reconnect answered with 503 by
the stopping server would end the browser's `EventSource`.

Background session summaries get 1 second; queued summaries are not started after that. With the
external backend a summary still running is stopped together with its command; a local-model
request cannot be interrupted, so it is abandoned when the process exits unless it finishes within
another half second. A warning names the sessions left without a summary; they keep none until
`summarize-missing` (CLI) or `POST /api/sessions/summarize-missing` backfills them.
An optional judgment underway gets 0.5 seconds, and queued beats are not judged. These limits keep a
stop inside launchd's exit timeout, 5 seconds for a job installed from the template (it sets no
`ExitTimeOut`), after which launchd sends SIGKILL. A service manager with a shorter stop timeout
needs a smaller phase timeout, for example
`--spring.lifecycle.timeout-per-shutdown-phase=1s`.

### Retired runner state

The task board and runner are retired. The runner CLI, deployment script, launchd template, and
example configuration are no longer supplied. Repository cleanup leaves existing machine-local
runner files, services, logs, registries, and worktrees untouched. Recovering or removing them is a
separate operator action; do not start an old runner against the updated API. See
[retirement and upgrade notes](board-retirement.md).

### Linux systemd

Copy [`scripts/black-box.service`](../scripts/black-box.service) to a user-managed unit file,
edit its JAR and database paths, and create the database's parent directory. For example, after
preparing `/path/to/black-box.service`:

```bash
systemctl --user link /path/to/black-box.service
systemctl --user daemon-reload
systemctl --user enable --now black-box
```

The template does not set `WorkingDirectory`. Configure one at the checkout, or set
`SBA_SUMMARY_EXTERNAL_COMMAND` to an absolute wrapper path, before using external summaries.
The same relative-command consideration applies to the launchd template.

### Docker for local development

Build from a checkout whose JAR is not serving a running process:

```bash
mvn clean -DskipTests package
docker build -t black-box .
docker run --rm -p 127.0.0.1:8766:8766 -v black-box-data:/data black-box
```

The image binds all interfaces inside the container; this command publishes only host loopback.
The named volume retains the SQLite database. The local image copies only the JAR: it does not
include the default external summary wrapper, provider CLI, or credentials. Explicitly provision
those for external summaries, or choose `SBA_SUMMARY_BACKEND=local` and configure a reachable
OpenAI-compatible local server. With local AI disabled/unavailable, that backend falls back to a
compacted transcript. The cloud build uses [`Dockerfile.cloud`](../Dockerfile.cloud).

## Configuration

Defaults are defined in [`application.yml`](../src/main/resources/application.yml),
[`application-postgres.yml`](../src/main/resources/application-postgres.yml), and
[`AuthSettings`](../src/main/java/dev/nathan/sbaagentic/platform/internal/adapter/in/web/security/AuthSettings.java).
These tables cover the application variables; hook and wrapper variables are separate below.

### Server, storage, authentication, and navigation

| Variable | Default | Purpose |
| --- | --- | --- |
| `SBA_PORT` | `8766` | HTTP port |
| `SBA_BIND_ADDRESS` | `127.0.0.1` | Bind address; authentication is optional and disabled by default. Enable [authentication](authentication.md) and HTTPS before network exposure. |
| `SBA_DATASOURCE_URL` | `jdbc:sqlite:sba-agentic.db` | Default SQLite location; the PostgreSQL profile defaults to `jdbc:postgresql://localhost:5432/blackbox`. |
| `SPRING_PROFILES_ACTIVE` | No PostgreSQL profile | Set `postgres` to select the PostgreSQL driver, schema, and SQL behavior. |
| `SBA_DATASOURCE_USERNAME` | `blackbox` | PostgreSQL database role |
| `SBA_DATASOURCE_PASSWORD` | Empty | PostgreSQL password, supplied through a protected runtime environment |
| `SBA_DATASOURCE_POOL_SIZE` | `8` | PostgreSQL maximum connections; default SQLite pool size is fixed at 4 |
| `SBA_AUTH_ENABLED` | `false` | Enable the browser-session and agent-bearer authentication boundary |
| `SBA_AUTH_USERNAME` | `blackbox` | Browser login name |
| `SBA_AUTH_PASSWORD` | Empty | Independently generated browser secret; required when authentication is enabled |
| `SBA_AUTH_API_TOKEN` | Empty | Separate independently generated agent bearer secret; required when authentication is enabled |
| `SBA_AUTH_SECURE_COOKIES` | `true` | Secure browser cookies; disable only in trusted loopback HTTP fixtures |
| `SBA_REDACT_ENABLED` | `true` | Redact secret-looking text and structured secret fields before persistence |
| `SBA_EDITOR_ENABLED` | `true` | Enable catalog-bound open-in-editor actions |
| `SBA_EDITOR_COMMAND` | Cursor application CLI on macOS | Absolute Cursor/VS Code-compatible executable |
| `SBA_EDITOR_ALLOWLIST` | Fixed Cursor and VS Code CLI locations | Comma-separated absolute executable allowlist; not automatic discovery of installed editors |
| `SBA_EDITOR_TIMEOUT` | `5s` | Maximum editor/Finder handoff time |
| `SBA_EXPORT_OBSIDIAN_DIR` | Empty | Configure the built-in Markdown summary export target; export is explicitly requested through API/UI |
| `SBA_PROJECTS_VOICE_CANONICAL_SCOPE` | Empty | Optional verified voice project path. When set, exact dated Codex voice-session directories (`~/Documents/Codex/YYYY-MM-DD/realtime-voice-chat[-N]` or `YYYY-MM-DD-new-realtime-voice-chat`) are grouped under it as reversible `codex-voice` aliases. Recorded session paths are preserved and captures are never classified by a project mentioned in conversation. Leave unset to disable; existing `codex-voice` aliases can be removed with `DELETE /api/project-aliases?aliasKey=...`. See [ChatGPT MCP gateway](chatgpt-mcp.md). |

Ingestion redaction applies before storage to event text, tool input/output, and metadata. The
stored human turn, session title and first human turn, the SQLite full-text index and the optional
Elasticsearch document are derived from those redacted values. It also
applies to newly saved melds and braids: title, body, caller-declared provider/model/prompt version,
and metadata. The save response and subsequent reads contain the accepted sanitized values.
Canonical project ownership, ordered input IDs, server-derived source provenance, execution mode,
and artifact kind validation are unchanged. Artifact kind is determined from the validated request
before metadata sanitization, so custom rules that alter `metadata.kind` cannot reclassify a braid.
No existing saved artifacts or captures are scrubbed by this write policy.

With the default patterns, nested JSON member names are matched case-insensitively after removing
non-alphanumeric separators. Names containing `apikey`, `secret`, `token`, `passwd`, `password`,
`passphrase`, `authorization`, `credential`, or `privatekey`, and environment-style names (capital
letters, digits and underscores) ending in `_KEY` such as `STRIPE_KEY`, replace the **entire value**
with `[REDACTED]`, including short strings, numbers, nulls, lists, and objects. This changes the
JSON type for numeric fields such as `tokenCount` or `inputTokens`: `1234` becomes the string
`"[REDACTED]"`, including in saved meld metadata. The
[durable hook](durable-capture.md#privacy-and-limits) applies the same policy without `passphrase`
and the `_KEY` names. Ordinary identity and metadata fields keep their structure.

Default text rules also scan named assignments inside string leaves, including JSON text returned
by tools. A name in text is a run of up to 256 letters, digits, underscores and hyphens; a longer
run is not read as a name. A name is a credential name in every text form when it contains
`password`, `passwd`, `passphrase`, `secret`, `token`, `authorization`, `api_key`/`apikey` or
`private_key`/`privatekey` in any letter case, anywhere in the name, so `client_secret`,
`access_token`, `privateKey` and `SPRING_DATASOURCE_HIKARI_DATA_SOURCE_PROPERTIES_PASSWORD` count.
Two weaker signals count only in configuration forms, because ordinary code uses them too:

- An environment-style name ending in `_KEY` or containing `CREDENTIAL` (`STRIPE_KEY`,
  `SBA_DB_CREDENTIAL`) counts in `=>` pairs, as a quoted key (`"STRIPE_KEY": …`), and in
  `NAME=value` or `NAME: value` with the value on the same line. A spaced code assignment
  (`ENTER_KEY = 13`), a value on the next line (a block colon such as `!= FAKE_KEY:`) and, outside
  `=>` pairs, a shell reference (`EVENT_KEY="$(…)"`, `SSH_KEY=$HOME/…`, `${NAME}`) are kept. A
  `$`-prefixed literal such as a bcrypt hash (`$2b$10$…`) is not a reference.
- A name with lowercase letters that contains `credential` counts only in `=>` pairs, so
  `credentials: 'include'` and `"credential-management":` are kept. Structured JSON members and
  property-list keys still use the full policy above.

Assignments accept `=`, `:` or `=>`, bare, matching single/double quoted, JSON-escaped quoted or
unicode-escaped quoted (`\u0022`) keys, and values of any length. Quoted values can contain
whitespace and backslash-escaped quotes; bare values end at whitespace, a JSON comma/closing
bracket/brace, a JSON-escaped line break or tab at any encoding level (`\n`, `\t`, `\\n`), or a
quote, raw or backslash-escaped, that closes the string holding the value: one followed by
whitespace, `,`, `}`, `]`, `)`, `;`, `:`, an escaped line break or tab, or the end of the text, or
one directly after a `[REDACTED]` marker. So `{"cmd": "export DB_PASSWORD=…"}` keeps its closing
quote, and so does a value ingestion already replaced when stored text is redacted again for a
summary backend. Complete `[REDACTED]` markers inside a bare credential are consumed as whole spans,
so repeated markers cannot expose a trailing credential suffix. Genuine closing delimiters still end
the value.
Other text is preserved. An unclosed quoted credential consumes the remainder of the scanned scalar.
Characters glued to a closing quote, as in the shell word `"abc"def`, are redacted with the value; a
delimiter, another quote or a JSON escape after the quote ends it.

The same names also match displayed service configuration:

- `=>` pairs, as printed by `plutil -p` (`"SBA_JUDGE_API_KEY" => "…"`), `launchctl print`
  (`SBA_JUDGE_API_KEY => …`), Ruby and PHP, also with an HTML-safe `=\u003e`. A `=>` value is never
  read as a shell reference, so `SBA_SIGNING_KEY => $…` loses it.
- A name that starts its displayed line is a key/value pair in any letter case (`JevApiKey => …`,
  `db_password => …`, `sba.judge.password => …`). It starts its line when only indentation stands
  before it, optionally after a `cat -n`, file-view or `grep -n`/`rg` gutter with or without a path
  (`24:`, `path:12:`, `path-13-`), quote marks, a Markdown heading, a list or diff marker, an
  opening code span or bold marker, or the dotted prefix of the name when it holds up to 128
  characters (a longer one counts only when a single segment takes it past 128, and never past 256).
  An unquoted value after such a name runs to the end of its line, so its spaces, commas, quotes and
  backslashes go too; inside a code span or bold text that opened before the name it ends at the
  closer, so `` `JevApiKey => …` is shown`` keeps the prose after it. A spaced `name => value` whose
  name has lowercase letters is kept as a lambda or arrow-function parameter when the name does not
  start its line (`tokens.map(token => …)`) or when the value opens a block or parenthesis or uses
  the name itself (`token => token.trim()`). The same name inline in prose keeps the rest of its
  line.
- `launchctl print` indents its entries with tabs, so lines after an entry that are not
  tab-indented are the rest of a value that holds line breaks; they are taken when another
  tab-indented line follows them.
- `plutil -p` does not escape quotes inside a value, so a quoted value after a quoted name that
  starts its line ends at the last quote on that line. A `plutil -p` data value
  (`{length = 16, bytes = 0x…}`) is removed whole.
- `PlistBuddy -c Print` shows an entry as `NAME = value` through the end of its line: an
  environment-style name with a credential word followed by ` = ` at the start of a line
  (`SBA_DB_PASSWORD = …`) loses the rest of the line.
- JSON-encoded text, such as a tool result the capture hook stored with `jq tojson`:
  backslash-escaped key and value quotes (`\"api_key\": \"…\"`, up to four encoding levels) are
  matched like ordinary quotes, and a value ends at the matching escaped quote. A displayed line's
  encoding level is read from the escaped line break, tab or quote that starts it, so a quote or a
  literal backslash inside a value belongs to it, and the value ends at its own line's escaped line
  break or at the quote that closes its string. Because an escaped line break ends a bare value,
  each entry of an escaped `env` or `launchctl print` listing is matched on its own.
- Property-list XML: a `<key>` (closed by `</key>` or the slash-escaped `<\/key>`) whose name the
  structured policy above treats as secret keeps its tags but has the next `<string>`, `<data>`,
  `<integer>` or `<real>` value replaced, when only non-markup text of up to 1,024 characters and
  at most four XML comments stand between them. Same-line and later-line values, raw or
  JSON-escaped whitespace, `cat -n` and file-view line numbers, `grep -n`/`rg` gutters with or
  without a path (`path:12:` and `path-13-`), diff markers, and JSON or Python list separators all
  fit. A value element directly after a replaced one, such as the old and new lines of a diff, is
  replaced too. When a key and its value are separate strings of one JSON list, such as the lines
  of an Edit tool's `structuredPatch`, the value at the start of the next string is replaced. A
  token count keeps its integer (`<key>tokenCount</key><integer>42</integer>`). A `<string>` that
  holds a credential flag (`<string>--api-key</string>`, see below) names the next `<string>` the
  same way, and its chain of replaced values stops at the next flag.
- Credential flags: a flag of up to 130 characters whose name ends with a credential word
  (`--api-key`, `--db-password`, `--client-secret`, `--auth-token`) loses its argument: the next
  word on a command line, quoted or bare, the next element of a list quoted the same way
  (`["--api-key", "…"]`, `plutil -p` arguments), or the next line when the flag is alone on its line
  (`launchctl print` and PlistBuddy arguments) and that line is not a help description indented
  under it (see below). Flags that name where a credential is, read one or page results
  (`--secret-id`, `--token-file`, `--api-key-env`, `--password-stdin`, `--next-token`) and token
  limits (`--max-tokens`) keep theirs. `--api-key=value` is an ordinary assignment.
- What a flag is followed by in help output, manual pages and prose is kept in these cases only;
  any other word after a credential flag is taken as its argument (see the limits below):
  - A word from a fixed list that prose puts after a flag, in lower case and standing alone:
    English function words (`a`, `also`, `an`, `and`, `are`, `as`, `at`, `be`, `but`, `by`, `can`,
    `does`, `for`, `from`, `has`, `if`, `in`, `into`, `is`, `it`, `must`, `not`, `of`, `on`, `or`,
    `should`, `than`, `that`, `the`, `then`, `this`, `to`, `was`, `when`, `which`, `will`, `with`,
    `without`) and `flag`, `flags`, `option`, `options`, `prompt`, `read`, `string`, `support`,
    `usage` and `value`. Standing alone means that whitespace, a raw or JSON-escaped line break or
    tab, a quote that closes its string or the end of the text follows the word, directly or after
    one or two of `.`, `,`, `;`, `:` and `)`. An argument that only starts with such a word
    (`not-a-secret`, `will.i.am`, `value@prod`) is taken.
  - In a usage synopsis, a metavar (see below) between a `[` directly before the flag and a `]` or
    `|` after it (`[--api-key API_KEY]`, `[--token TOKEN | --token-file PATH]`). An argument slice
    such as Go prints for `os.Args` (`[--api-key F4KE… --port 8080]`) is taken.
  - A help entry's metavar and description. A help entry starts its line with the flag, after any
    short alias (`-p, --password`, `-k API_KEY, --api-key`), and follows it with an optional metavar
    after one space (an upper-case word without digits such as `API_KEY`, a bracketed name such as
    `<token>`, or a type name such as `string` or `stringArray`) and then a description, either in a
    column after two or more spaces or a tab, or on the next line indented at least two columns
    deeper than the entry, after the same kind of line-number or path gutter. A description reads as
    prose: its first word is up to 20 letters, optionally followed by one of `.`, `,`, `:` and `;`,
    and another word follows that opens neither a `#` comment nor a parenthesized note. Where the
    description stands right after the flag, its first word must also be lower case, capitalized or
    an acronym of up to five capitals (`Read token from standard input`, `prompt for passwords`,
    `API key for the service`). A line that ends with a shell continuation (`\`, or a `` ` `` or `^`
    after a blank) is a command. So an aligned argument with digits or symbols
    (`--api-key   F4KE…   prod key`), one that a comment or note follows
    (`--db-password    hunter2   (from file)`) and one on a continued line (`--api-key   VALUE \`)
    are still taken.
- Commands that write a credential: `launchctl setenv NAME value`, PlistBuddy `Set :…:NAME value`
  and `Add :…:NAME type value` (through the end of the `-c` argument), `plutil -replace` or
  `-insert` with a key path ending in the name and a type, `defaults write domain NAME [-type]
  value`, and the `-w` password of `security add-generic-password` or `add-internet-password`.
  The name follows the structured policy above, so `_KEY` names count.
- HTML-safe JSON escapes for `<` and `>` (`\u003c`, `\u003e`) and unicode-escaped quotes
  (`\u0022`, `\u0027`) are decoded in the first scan's result, which is then scanned again. Values
  the first scan replaced stay replaced, so the second scan only adds replacements; when it adds
  one, the stored string keeps the decoded characters. A quoted value that holds its own quote as
  such an escape (`'don\u0027t …'`, System.Text.Json's `\u0022`) is replaced whole.
- The provider key shape `apikey_<hex>[_<hex>…]` with at least 16 hexadecimal digits is replaced
  where a token starts: after a non-word character, a JSON-escaped line break or tab, a
  percent-encoded byte (`%22`, `%3D`) or a `\uXXXX` escape. With at least 32 digits it is replaced
  wherever it starts, such as after the length byte a binary property list stores before a string
  (`Hapikey_…` in `strings` output). Text glued after the hexadecimal digits stays
  (`apikey_…PID`). Shorter identifiers such as `apikey_cafe` are kept.

A token count is not a credential: a name containing `tokens` or `tokenCount` and no other secret
word (`max_tokens`, `context_tokens`, `promptTokenCount`) keeps an unquoted value of one to twelve
digits that is followed by whitespace, a JSON delimiter, an escaped line break, or closing
punctuation not glued to more letters or digits. Other values of those names are still redacted.
Structured JSON members named that way are unchanged: their entire value is still replaced.

Input is clipped before scanning at 50,000 UTF-16 code units with Unicode-safe clipping and the
existing truncation marker; replacement markers can expand short values. A text that already ends
with that marker, such as stored text redacted again for a summary backend, keeps a single marker:
only the text before it is scanned, and clipped if it is longer than the budget. The rules are
written so that their work grows linearly with the text they scan: the rules for names and write
commands try their patterns only where a match can start, the flag rule reads each run of flag
characters once, and a run of backslashes or the stretch before a line's start is read once, however
many names or flags it holds. Tests scan 4 MB of near-miss text for each rule within a fixed time
bound, including PlistBuddy, `launchctl setenv` and `defaults write` commands for credential names
on JSON-escaped lines, and near misses that one escaped markup character sends through the second,
decoded scan. That second scan reads the whole scalar again, so a scalar that holds such an escape
costs about twice as much as one that does not. These bounds cover each scanned scalar, not a
capture, whose cost still grows with its size and number of strings. This is a small assignment
grammar, not a shell parser, JSON decoder, or general detector for encoded or unlabelled secrets.
Known limits:

- A credential shown without its name is not recognized, such as `echo "$TOKEN"`,
  `launchctl getenv NAME`, the output of `security find-generic-password -w`, a positional
  argument, or an Edit whose old and new strings hold only a `<string>` line.
- An unquoted `NAME=value` or `name: value` ends at whitespace, so a value with spaces printed by
  `env` or `printenv`, or a YAML value with spaces, keeps everything after its first space. Only
  `=>` values, `NAME = value` with an environment-style name, and flags alone on their line run to
  the end of the line, and only when the name starts its line.
- A `name => value` whose name has lowercase letters is kept when the name does not start its
  line, and when the value opens a block or parenthesis or uses the name itself.
- An environment-style `_KEY` or `CREDENTIAL` name assigned with `=` or `:` to a value that reads
  as a shell reference (`$NAME`, `${…}`, `$(…)`) keeps it, so an `env` listing whose such value
  starts with `$` and a letter keeps it. With `=>` the value is replaced.
- `PlistBuddy -c Print` and `defaults read`: a `_KEY` or `CREDENTIAL` name followed by ` = `
  (`STRIPE_KEY = …`) keeps its value, a name with lowercase letters followed by ` = `
  (`password = …`) keeps everything after the first word of its value, and an unquoted list element
  after a quoted flag, as `defaults read` prints an alphanumeric string, is kept.
- Flags: single-dash flags (`-p`, and `-w` outside `security add-…-password`), an argument on the
  line after a shell `\` continuation, whether or not the flag starts its line, an argument that
  starts with `-`, an unquoted list element (code such as `["--api-key", key]`), and the argument of
  `--secret-key`, `--access-key` or `--signing-key`, whose names end with `key` rather than a
  credential word, are kept. An argument that only names where the credential is or a documentation
  placeholder (`--api-key env:NAME`, `--secret xxx`) is replaced anyway.
- Help layouts and prose words are recognized by shape, not by content, so an argument laid out
  like them is kept. The open layouts, all for a flag that starts its line unless noted:
  - after two or more spaces or a tab, a word that could start a sentence followed by another word
    (`--password   letmein   for the demo`, or a tab-separated row
    `--db-password<TAB>hunter<TAB>staging`);
  - after one space, a metavar-shaped argument followed by a description column
    (`--api-key ABCDEFQWERTY   set for the demo`);
  - a line indented at least two columns deeper than a flag that is alone on its line, or followed
    only by a metavar-shaped argument, when that line starts with a word of letters and holds another
    word (`--password` above `      letmein for the demo`);
  - anywhere on a line, a metavar-shaped argument of a flag directly after `[` when `]` or `|`
    follows it (`[--api-key ABCDEFQWERTY]`);
  - anywhere, an argument that is exactly one of the prose words above (`--password value`,
    `--token not`).

  Help and prose outside these layouts lose the word after a credential flag: a description after a
  single space (`--with-token Read token …`), a description column right after the flag that starts
  with a digit, a symbol, a mixed-case word or more than five capitals
  (`--github-token   GitHub token`), a description of one word or one that a note follows
  (`--secret   Secret (required)`), a description after a lone colon, which reads as a
  `name : value` pair (`--federated-token : Federated token …` loses `Federated`), a placeholder
  (`--password password`, `--change-passphrase user-id`), a GNU `--password=PASS` metavar, which is
  an assignment, and prose that puts any other word after a flag (`Added the --api-key parameter`,
  `--token now reads …`, `Make --api-key required.`).
- Names: a quoted name with dots is not matched (`"sba.judge.password" => …`,
  `{"sba.judge.password": …}`, `defaults read`'s `"sync.auth.token" = …;`), nor is a lowercase or
  mixed-case name that ends with `key` but holds no credential word (`jev_key`, `JevKey`), a name
  written as a code subscript or argument (`plist['EnvironmentVariables']['SBA_DB_PASSWORD'] = …`,
  `os.environ["SBA_X_TOKEN"] = …`, `System.setProperty("sba.judge.password", …)`), or a run of more
  than 256 name characters. A list or object written in text under a credential name is not
  removed whole: only its text up to the first delimiter goes, so `"api_tokens": ["…", "…"]` keeps
  its second element and `"SBA_SECRETS": {"db": "…"}` keeps its values; a structured JSON member
  still loses its whole value.
- Displays and restatements: `launchctl list` prints `ProgramArguments` as a `;`-separated list,
  whose element after a credential flag is kept; `defaults write … -dict` and `-dict-add NAME value`
  keep the value; Markdown restatements (`` `NAME` => `value` ``, `**NAME**: value`, a table row)
  keep it; a `launchctl print` value that starts with a quote loses only the quoted part, and the
  rest of its line is kept; a property-list pair in a `diff` normal-format listing (`<` and `>`
  lines) is kept, while unified, context and side-by-side listings are covered.
- Gutters and colors: only the `cat -n`, file-view arrow and `grep -n`/`rg` gutters described above
  are recognized. Behind another prefix, such as `path:line:column:`, a `grep` path with spaces,
  `less -N` or `awk` line numbers, `bat`'s grid, `cat -A`'s `^I`, a timestamp or ANSI color codes,
  a name does not start its line, so a lowercase `name => value` there is kept and other values end
  at whitespace.
- A multi-line `launchctl print` value keeps a later line that starts with a tab, and all of its
  later lines when no tab-indented line follows them (the end of the text or a blank line comes
  first).
- A property-list value is missed when a merge-conflict marker or other markup stands between it
  and its key, when more than 1,024 characters do, or when an XML comment between them contains
  `>`. HTML-entity-escaped markup (`&lt;key&gt;`) and percent-encoded assignments are not decoded.
- Code is redacted too when it assigns to a credential name: `passphrase: cert.passphrase`,
  `privateKeyLoc = null` and `PrivateKeyword = 123` lose their values, as `password: …` and
  `token = …` already did, and an environment-style `_KEY` code followed by a colon loses the next
  word (`INVALID_DOTENV_KEY: [REDACTED] key part`). An environment-style credential name followed by
  ` = ` at the start of a line loses the rest of that line (`SECRET_KEY = os.environ.get(…)`), and a
  quoted `=>` key at the start of a line loses its line through the last quote, including later
  pairs on a one-line Ruby or PHP hash. A long name holds a credential word more often by chance:
  the localized TypeScript message key
  `The_parser_expected_to_find_a_1_to_match_the_0_token_here_1007` loses its message, and the GNU
  help metavar in `--authentication-openid-connect-client-id-token-file=name` is replaced.

Model export also applies these default rules independently of ingestion settings and retains its
conservative fallback for escaped quoted credential keys, which may remove the rest of a text leaf;
that fallback uses the first word list only, not the weaker signals.

String member names are also scanned with the active text-redaction patterns. If redacting a name
would collide with another member, the changed name gains a ` (redacted key N)` suffix so that the
other field is preserved. Custom `sba.ingestion.redact-patterns` replace the default text patterns
**and disable the default secret-key classification**; those custom patterns still scan string
values and member names. Disabling ingestion redaction leaves those inputs unchanged. This is
best-effort sanitization, not a guarantee that arbitrary secrets are recognized, and does not
retroactively scrub existing captures or saved artifacts. When enabled, the 50,000 UTF-16 code-unit
scan ceiling applies with either default or custom patterns to each meld/braid free-text field
and each metadata string scalar, with Unicode-safe clipping and a truncation marker; trailing
unscanned content is discarded. Disabling
redaction also disables this scan clipping. Event ingestion has separate text-length limits.
Summary/model export has a separate, mandatory redaction boundary; configuring ingestion does not
replace it. Session summaries and direct meld previews pass each piece of stored text they send to
the configured summary backend, local or external, through the default credential rules above,
independently of ingestion settings, so a capture stored before a rule existed is not sent as
stored. They do not use export's whole-leaf fallback, so stored text that holds no recognized
credential, such as a JSON token count (`"max_tokens": 4096`), reaches the backend unchanged. A stored
tool input or output is JSON whose strings ingestion redacted one by one, so it is parsed and each
string, member names included, is redacted the same way, and a member whose name is a secret key
loses its whole value: a string that ends with a name and separator (a Grep pattern `password:`) or
with a value ingestion replaced never runs on into the JSON after it, and a tool input or output
that holds no recognized credential reaches the backend exactly as stored, valid JSON at any length.
Across the strings of a list, the element after a credential flag that is a whole element
(`["--api-key", "…"]`) and a private key whose lines are separate elements, as in an Edit's
`structuredPatch`, are replaced too, although ingestion keeps them. A name and separator that end
one string are not read with the next string, here or at ingestion, so `["password:", "…"]` keeps its
second element. Stored text that is not JSON is redacted whole. A stored string that ingestion
clipped keeps its one truncation marker; if replacements grew it past the scan budget, it is clipped
there again, still with one marker. A direct meld preview redacts each piece (session title,
summary, evidence text, tool input and output) before it is clipped into the bundle, so no piece
costs the bundle its later sessions; the bundle the preview returns locally is built from stored
text, as before. The summary and title that come back are redacted with the ingestion rules before
they are stored.

Summary Markdown export resolves its explicitly configured root to a canonical directory; a
configured root alias is supported. Descendant directory symlinks and symbolic-link/non-regular destination
notes are rejected. Existing hard-linked notes are replaced without changing their other aliases. Ordinary repeat exports replace the completed note atomically, preserving
existing POSIX permissions where supported. Failed rendering, staging or atomic publication leaves
the previous note intact; filesystems without stable file identities or atomic replacement fail
closed. New staged files use the platform's private temporary-file permissions.

Export directories must be caller-controlled and remain stable during the operation. Identity
checks detect observed directory/file replacements, but portable Java filesystem operations do
not guarantee resistance to adversarial concurrent directory renames. This is not a crash-durability
or cross-process editing protocol. Normal failures clean up staging files. If a directory moves
mid-export, a `.blackbox-export-*.tmp` file may remain in the displaced directory: cleanup avoids
following the replacement path. Inspect that orphan only after restoring a trusted directory layout.

Authentication represents one trusted workspace with one browser user and one agent token, not
tenant isolation or per-agent permissions. Both secrets must be independently generated, different,
at least 32 characters, and satisfy startup validation; never put secret values in command
arguments or tracked files. See [Authentication](authentication.md) for the complete boundary.

PostgreSQL support starts with one authoritative API replica. It adds neither local/cloud sync nor
multi-replica coordination. The opt-in contract test reads `SBA_POSTGRES_TEST_URL`,
`SBA_POSTGRES_TEST_USERNAME`, and `SBA_POSTGRES_TEST_PASSWORD`; these select a disposable test
database, not the service database. See [PostgreSQL](postgres-backend.md).

### Summaries and local AI

| Variable | Default | Purpose |
| --- | --- | --- |
| `SBA_SUMMARY_BACKEND` | `external` | Summary backend; choose `local` explicitly for local model use |
| `SBA_SUMMARY_EXTERNAL_COMMAND` | `scripts/summarize-with-codex.sh` | External summary command, executed through `/bin/sh -c` |
| `SBA_SUMMARY_TIMEOUT` | `10m` | External summary subprocess timeout |
| `SBA_LOCAL_AI_ENABLED` | `true` | Enable OpenAI-compatible local model calls |
| `SBA_LOCAL_AI_BASE_URL` | `http://localhost:1234` | Local model base URL |
| `SBA_LOCAL_AI_CHAT_PATH` | `/v1/chat/completions` | Chat endpoint path, resolved against the base URL; an absolute URL (a scheme or a leading `//`) names its own host |
| `SBA_LOCAL_AI_MODEL` | `local-model` | Configured local model identifier |
| `SBA_LOCAL_AI_API_KEY` | `lm-studio` | Local-compatible API credential; replace when the selected server requires it |
| `SBA_LOCAL_AI_MAX_INPUT_CHARS` | `8000` | Per-request input window; larger transcripts are map-reduced |

Summaries are scheduled automatically in the background when a session records a terminal
event (`SessionEnd`, `Stop`, or `SubagentStop`) and has no summary yet; `summarize` and
`summarize-missing` run them explicitly. A restart stops unfinished background summaries; see
[Stopping and restarting](#stopping-and-restarting). Default external summarization can send transcript text
through the selected vendor; that text passes export redaction first (see
[Configuration](#configuration)), which removes recognized credentials but is not a guarantee. Both
[`summarize-with-codex.sh`](../scripts/summarize-with-codex.sh) and
[`summarize-with-claude.sh`](../scripts/summarize-with-claude.sh) are supplied. Select the latter
with `SBA_SUMMARY_EXTERNAL_COMMAND=/path/to/black-box/scripts/summarize-with-claude.sh`.
`SBA_SUMMARY_BACKEND=local` chooses LM Studio or another OpenAI-compatible server at
`SBA_LOCAL_AI_BASE_URL`. "Local" names the backend, not a network restriction: the URL defaults to
loopback, but a remote URL receives the transcript text. It falls back to compacted transcript text
if local summarization cannot run or `SBA_LOCAL_AI_ENABLED=false`. This configured summary
subprocess does not execute queued work or launch worker agents.

### Continuation restatement (optional)

`POST /api/continuations/synthesize` can ask the local model above to restate one deterministic
continuation prompt. It is off by default; `GET /api/continuations` never calls a model.

| Variable | Default | Purpose |
| --- | --- | --- |
| `SBA_CONTINUATION_SYNTHESIS_ENABLED` | `false` | Create the restatement adapter; without it every request reports `disabled` |
| `SBA_CONTINUATION_SYNTHESIS_ALLOW_NON_LOOPBACK` | `false` | Allow a chat completions URL (`SBA_LOCAL_AI_CHAT_PATH` resolved against `SBA_LOCAL_AI_BASE_URL`) whose host is not `localhost`, `127.0.0.0/8` or `::1` |
| `SBA_CONTINUATION_SYNTHESIS_MAX_OUTPUT_TOKENS` | `512` | `max_tokens` for the model call, when one is made |
| `SBA_CONTINUATION_SYNTHESIS_MAX_OUTPUT_CHARS` | `4000` | Longest reply accepted; a longer reply is rejected as `truncated` |

When enabled, each POST recomputes the deterministic candidates and makes at most one call to the
chat completions URL (with `SBA_LOCAL_AI_ENABLED=true`), using the local client's timeout. It makes
none for a 400 or 404 (unknown candidate) or when the reason is `disabled`,
`remote_model_not_allowed` or `input_over_budget`. That URL is `SBA_LOCAL_AI_CHAT_PATH` resolved
against `SBA_LOCAL_AI_BASE_URL`, exactly as the client calls it, so the loopback rule also refuses
an absolute chat path that names another host. The model sees evidence handles (`[E1]`), never event
IDs, and every captured string passes through export redaction. Input longer than
`SBA_LOCAL_AI_MAX_INPUT_CHARS` (minimum 500) is refused, not elided. The reply is rejected whole
unless it is non-blank, finished with `finish_reason: stop`, within the output bound, cites at least
one handle in square brackets, mentions no handle other than the known ones (any `E` followed by
digits, in any case, bracketed or not, such as `(E9)`, `[E9; E1]` or `[e9]`) and mentions no other
event ID. Failures return `synthesized: null` with one reason: `disabled`,
`remote_model_not_allowed`, `input_over_budget`, `unavailable`, `timeout`, `truncated`,
`invalid_reference` or `empty`. Prompt text and model replies are never logged; a failed call logs
only the exception class. Nothing is cached or retried, and the deterministic prompt is unchanged. A
non-loopback URL with `SBA_CONTINUATION_SYNTHESIS_ALLOW_NON_LOOPBACK=true` sends that evidence to
the configured host.

The wrappers have their own environment settings:

| Variable | Checked-in default | Purpose |
| --- | --- | --- |
| `SBA_SUMMARY_CODEX_BIN` | Homebrew Codex executable location | Override with the installed Codex executable |
| `SBA_SUMMARY_CODEX_AUTH` | `auth.json` in the configured Codex state directory | Wrapper credential source; honors `CODEX_HOME` for its default directory |
| `SBA_SUMMARY_CODEX_MODEL` | Unset | Omit `--model` unless explicitly configured |
| `SBA_SUMMARY_CLAUDE_BIN` | `claude` | Claude executable |
| `SBA_SUMMARY_CLAUDE_MODEL` | `claude-opus-4-8` | Wrapper model argument; check support in the installed client/provider |
| `SBA_SUMMARY_CLAUDE_EFFORT` | `max` | Wrapper effort argument |

These are source defaults, not a claim that a particular client or provider currently accepts
every model argument. External wrappers require their CLI and credentials in the service context.

### Memory recall and Elasticsearch

| Variable | Default | Purpose |
| --- | --- | --- |
| `SBA_MEMORY_EMBEDDING_ENABLED` | `true` | Enable structured-intent semantic recall; unavailable embeddings leave lexical recall usable |
| `SBA_MEMORY_EMBEDDING_URL` | `http://localhost:11434` | Ollama-compatible memory embedding base URL |
| `SBA_MEMORY_EMBEDDING_PATH` | `/api/embeddings` | Embedding request path |
| `SBA_MEMORY_EMBEDDING_MODEL` | `nomic-embed-text` | Model for structured intent and stored summary vectors |
| `SBA_MEMORY_EMBEDDING_DIMENSIONS` | `768` | Expected vector dimensions |
| `SBA_MEMORY_EMBEDDING_DOCUMENT_PREFIX` | `search_document: ` | Stored-text prefix, including trailing space; empty disables |
| `SBA_MEMORY_EMBEDDING_QUERY_PREFIX` | `search_query: ` | Query prefix, including trailing space; empty disables |
| `SBA_MEMORY_RECALL_RELEVANCE_FLOOR` | `0.61` | Cosine floor for semantic-only additions; values at or below zero disable it |
| `SBA_RECALL_TELEMETRY_PROJECT_ALIASES` | Empty | Comma-separated allowlist of safe declared telemetry project aliases |
| `SBA_SQLITE_VEC_PATH` | Empty | Optional SQLite native accelerator; empty uses portable Java cosine ranking. PostgreSQL skips extension loading. |
| `SBA_ELASTICSEARCH_ENABLED` | `false` | Enable optional secondary event indexing |
| `SBA_ELASTICSEARCH_URL` | `http://localhost:9200` | Elasticsearch base URL |
| `SBA_ELASTICSEARCH_INDEX` | `sba-agentic-events` | Event index name |
| `SBA_ELASTICSEARCH_REPLICAS` | `0` | Replica setting when creating the event index |

Semantic recall returns Decisions, Handoffs, Observations, Ideas, and Evidence. Projections are lexical-only;
summary vectors are stored but not returned by recall, and the full event corpus is not
semantically indexed. Elasticsearch is independent of memory embeddings. The floor is a dated
measurement, not a universal relevance guarantee; remeasure when the model or corpus changes.

### Ask

Ask is a separate retrieval path, and it is off under the shipped defaults. It requires
`SBA_ELASTICSEARCH_ENABLED=true` (default `false`) and an externally provisioned `agent-memory`
index: Black Box searches that index but never creates or writes to it. With either missing,
retrieval reports mode `unavailable`, every Ask query returns no citations, and the answer is the
fixed string "Answer not found in memory."

With Elasticsearch reachable and that index populated, query embeddings add hybrid retrieval and an
unavailable embedder falls back to BM25. The local chat model adds answer synthesis; when retrieval
found citations but synthesis fails, Ask returns those citations with a degraded-synthesis message.

`agent-memory` is a historical default that Black Box never writes. The only index this service
populates is `SBA_ELASTICSEARCH_INDEX` (default `sba-agentic-events`), and that is not the index Ask
reads. Point `SBA_ASK_MEMORY_INDEX` at an index you actually populate. `sba-agentic-events` is a
valid target for lexical retrieval, but it carries no vector field, so hybrid retrieval degrades to
BM25 against it.

| Variable | Default | Purpose |
| --- | --- | --- |
| `SBA_ASK_MEMORY_INDEX` | `agent-memory` | Ask retrieval index |
| `SBA_ASK_VECTOR_FIELD` | `vector` | Vector field in that index |
| `SBA_ASK_EMBEDDING_ENABLED` | `true` | Enable Ask query embeddings |
| `SBA_ASK_EMBEDDING_URL` | `http://localhost:11434` | Ask embedding base URL |
| `SBA_ASK_EMBEDDING_PATH` | `/api/embeddings` | Ask embedding request path |
| `SBA_ASK_EMBEDDING_MODEL` | `nomic-embed-text` | Ask embedding model |
| `SBA_ASK_EMBEDDING_DIMENSIONS` | `768` | Ask vector dimensions |
| `SBA_ASK_DEFAULT_CITATIONS` | `6` | Default answer citation count |
| `SBA_ASK_DEFAULT_RETRIEVE_RESULTS` | `10` | Default retrieval result count |
| `SBA_ASK_ANSWER_MAX_TOKENS` | `640` | Answer token budget |

### Hook environment variables

Capture and recall hooks are opt-in; see [Connect an agent](agent-integration.md) for registration.

| Variable | Default | Purpose |
| --- | --- | --- |
| `SBA_AGENTIC_URL` | `http://localhost:8766` | Capture/recall service URL |
| `SBA_AGENT_SOURCE` | Positional source argument, otherwise `unknown` | Capture source override |
| `SBA_CAPTURE_DURABLE` | `0` | Set `1` to enable the sanitized local outbox; its default URL is numeric-loopback `http://127.0.0.1:8766` |
| `SBA_CAPTURE_OUTBOX_DIR` | `~/.blackbox/outbox` | Private local capture queue directory |
| `SBA_CAPTURE_HTTPS_ORIGIN` | Unset | Exact normalized HTTPS destination opt-in; must match the selected URL and uses an origin-bound macOS Keychain bearer |
| `SBA_RECALL_WITHIN_HOURS` | `720` | Recall lookback, 30 days |
| `SBA_RECALL_LIMIT` | `3` | Maximum hook items |
| `SBA_RECALL_MAX_CHARS` | `4000` | Hook context-block budget, separate from MCP's default 24,000-character item-text clamp |
| `SBA_RECALL_CLIENT` | `unknown` | Client label; `--client` takes precedence |
| `SBA_RECALL_LOG` | `recall.log` in the user's Black Box state directory | Fire log; explicit empty value or `off` disables it |
| `SBA_RECALL_PURPOSE` | `normal` | Declared `normal`, `audit`, or `test` use; other values normalize to `unknown` |
| `SBA_RECALL_PROJECT_ALIAS` | `unknown` | Safe declared alias; must satisfy the format and server allowlist |

Hook success does not prove persistence or delivery: the bridges deliberately do not fail the
host turn. Legacy direct capture and recall hooks do not attach bearer authentication; setting
server-side `SBA_AUTH_API_TOKEN` does not wire these clients. The optional
[durable capture outbox](durable-capture.md#explicit-https-delivery) supports explicit HTTPS
delivery with a destination-bound credential. Fire logs contain paths and
session identifiers; keep them private. Server [recall telemetry](recall-observability.md) excludes
raw query/result text and does not prove that returned context was read or useful.

## Secure file navigation

Expanded Stream, Browse, Find, and Projects event cards make file references actionable only when
the path belongs to a filesystem-verified Git scope in the project catalog. The browser sends a
`CodeReference` with a `projectKey`, a relative path, and an optional line/column to
`POST /api/open-in-editor` or `POST /api/reveal-in-finder`, so a target is addressed by key plus
relative path rather than by a client-supplied absolute path. The key is a stable, URL-safe encoding
of the catalog root, not a secret: the safety property is the server-side revalidation below, not
path hiding. Paths outside eligible roots remain visible and copyable.

The server revalidates catalog membership, exact-scope confinement, symlinks, file existence, and
line bounds on each request. It invokes only a configured, allowlisted absolute executable with
discrete argv; file contents and event data are never evaluated by a shell. Cursor is the macOS
default, and VS Code uses the same `-g file:line:column` adapter. Finder reveal uses the same
resolver and a fixed `/usr/bin/open -R` command. Typed failures render beside the path.

## Client disconnects

A browser that closes a page, popup, or event stream mid-response is not a server failure. Tomcat's
typed `ClientAbortException` (directly or as a cause of a converter failure) and Spring's
`AsyncRequestNotUsableException` for streams are logged at DEBUG by `ApiExceptionHandler` and get no
response body, since a JSON error envelope cannot be written under an already-set content type such
as `font/woff2`. Enable `logging.level.dev.nathan.sbaagentic.platform.internal.adapter.in.web=DEBUG`
to see them. Plain `IOException`, `EOFException`, and other failures, including ones whose message
mentions a broken pipe or connection reset, still return the typed 500 `internal_error` body and an
ERROR log.

## Schema evolution

There is no migration framework such as Flyway or Liquibase. Spring's `sql.init.mode=always`
reapplies idempotent creation DDL in [`schema.sql`](../src/main/resources/schema.sql) on boot.
`CREATE TABLE IF NOT EXISTS` creates missing tables; it does not update existing table columns.

For existing SQLite databases,
[`RecordingSqlStore.ensureSchema`](../src/main/java/dev/nathan/sbaagentic/recording/internal/adapter/out/sqlite/RecordingSqlStore.java)
inspects `PRAGMA table_info(agent_sessions)` and performs guarded `ALTER TABLE` additions for
`title_rank` and `spawned_by`. Legacy titles receive a protective rank. The PostgreSQL profile
uses [`schema-postgres.sql`](../src/main/resources/schema-postgres.sql) and skips SQLite PRAGMAs,
FTS5 initialization, and native-extension loading. This is not a versioned migration history,
rollback facility, or database synchronization mechanism.

NAT-243 adds an idempotent, opt-in retirement migration for both database profiles. Fresh schemas
omit the board; existing databases preserve legacy data unless `SBA_RETIRE_WORKFLOW=true`
(`sba.storage.retire-workflow=true`) is explicitly set. With that flag, startup removes `specs`,
`tasks`, and `task_events` and nulls/drops `session_links.task_id`, while retaining session links
and recorded events, including historical completion Handoffs. Verify a database backup before
enabling retirement. Binary rollback cannot restore the removed task data or its schema;
do not run an old binary against the migrated database. See [retirement notes](board-retirement.md).

SQLite FTS5 is rebuildable; its triggers maintain the event index during canonical writes.
Do not run `VACUUM` on the live SQLite database without rebuilding FTS afterward: implicit event
rowids can change. LIKE fallback uses substring matching; it is not identical to FTS5 token-prefix
matching. See [Architecture](architecture.md#local-first-and-model-boundaries).

## Related guides

[Authentication](authentication.md) covers browser sessions, bearer requests, and HTTPS deployment.
[PostgreSQL](postgres-backend.md) covers shared storage and its single-server limits.
[Local writes and Elasticsearch](local-writes-and-elasticsearch.md) covers the optional local index.
The former managed AWS prototype has been retired and is not a running or public service; its
historical design is in [docs/lightsail-prototype.md](lightsail-prototype.md).

## Canonical event chronology indexes

Startup adds three derived expression indexes for global event chronology, session event chronology,
and the human-turn subset. They normalize the stored UTC timestamp only inside index/query keys, with
nine fractional digits and an ordered signed-year encoding; canonical event bytes and existing indexes
remain unchanged. Repeated startup reuses the indexes. The first startup after this upgrade builds them
against existing history, which adds a one-time startup/disk cost and ordinary index maintenance on
subsequent writes. SQLite remains the default.

Feed and session transcript cursors retain their existing wire format. They compare normalized time
and event ID together, so whole-second, fractional and nanosecond events do not disappear when pages
are merged with transcript-file events. A later-arriving older event can appear in a remaining page;
this is keyset navigation, not a frozen database snapshot. Newer arrivals above an already-consumed
cursor require a head refresh, as before.

A disposable 50,000-event SQLite fixture (1% intent, 99% hook events) built all three indexes in
98 ms, adding about 6.0 MiB; repeat initialization took 9 ms. The first page used the ordered index
(80 microseconds); global, session and human deep-page queries used indexed range seeks with no
temporary sort (73–89 microseconds). Retrieving 100 recent intent events through the real recall
adapter took 2.9 ms. These are measured warmed fixture results, not production latency guarantees.
Deployment time, index space and filtered-query cost depend on database size, storage and workload.

### Saved braid ownership upgrade

Startup now upgrades saved-meld ownership on both database profiles to support explicitly unassigned
braids. See [saved braids and recovery boundaries](projectless-braids.md) before upgrading existing
storage. Older binaries are unsupported after null-owned braid writes; use forward repair or an
explicitly restored verified backup. This does not attach artifacts to projects or synchronize databases.
