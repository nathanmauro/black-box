# Cortex Stage

Black Box's cortex stage is an optional ingest-time judgment pass for orbit-style live session
views. It folds freshly recorded events into short per-session beats, asks the versioned Jev
question set, persists the typed answers, and emits a lightweight SSE update. It is advisory
metadata over recorded events; it does not modify events, infer authority, or write lineage.

The stage is off by default:

```bash
SBA_JUDGE_ENABLED=false
SBA_JUDGE_PROVIDER=jev
```

When disabled, no beat scheduler, judgment executor, Jev HTTP client, API calls, or judgment rows
are created. Black Box capture, recall, and streaming work without this stage or any
Constellate process. Provider failures are isolated in a bounded background worker and do not
roll back recorded events; under pressure, the 256-beat queue drops older pending judgments.

## Beat Rule

Events fold per session while all of these remain true:

- the next event arrives no more than `sba.judge.beat.gap-ms` later, default `4000`;
- the open beat has fewer than `sba.judge.beat.max-events`, default `12`;
- folded text stays under `sba.judge.beat.max-chars`, default `1500`.

These normalized event types always close the open beat and stand alone:
`userpromptsubmit`, `decision`, `handoff`, `observation`, `projection`, `subagentstart`,
`subagentstop`, `sessionstart`, `sessionend`, `stop`, `manualcapture`, `quicknote`.
A scheduled tick flushes quiet open beats after the gap.

Each event contributes one readable line. Prompts are rendered as `Nathan: <text>`. Tool calls are
rendered as `<tool>(<command|file_path|pattern|...>) → <first output line>`.

## Question Set

The canonical resource is `src/main/resources/judge/questions.json`, version `orbit-jev-v1`.
Constellate's orbit prototype can later read this resource from Black Box instead of carrying
its own copy in its `orbit/` directory.

The request state sent to Jev has this shape:

```json
{
  "beat": { "events": [{ "line": "Nathan: ..." }] },
  "session": { "source": "codex", "repo": "/repos/black-box", "title": "..." },
  "trail": ["last beat title"],
  "others": [{ "k": 0, "source": "claude", "repo": "/repo", "title": "...", "latest": "..." }]
}
```

`kin_<k>` is asked once for each `others[k]`. The `human` question is asked only when the beat
contains a prompt-like event that is not a relayed machine marker. Tool-only beats use `human = 0`
by rule.

## Outbound Data

Enabling the Jev provider sends excerpts from every newly recorded repository and session to
`https://api.typesafe.ai/v1/systemone`; there is currently no repository/session allowlist.
There is no startup history scan. Newly ingested events with historical timestamps still qualify.

The state includes prompt text (up to 400 characters), selected tool arguments (160), the first
output line (120), other event text (300), source, repository, and session title (120). Context
includes the last five processed beat titles (72 characters each), and normally up to eight other
sessions active within 30 minutes with their source, repository, title, and last three beat titles.
Event/session IDs and timestamps are not request-state fields. The question set names Nathan.

Only an initial `/Users/<name>/` in repository fields is shortened to `~/`; embedded paths and
private project, code, or relationship content can remain. Mandatory export credential
redaction is applied to state text before serialization, in addition to configurable
ingest redaction. It is best-effort and is not a general outbound privacy filter. Review this scope before
enabling the provider against a real capture database. Use a fixture database and fake
`JevTransport` for verification without provider egress.

## Configuration

```yaml
sba:
  judge:
    enabled: ${SBA_JUDGE_ENABLED:false}
    provider: ${SBA_JUDGE_PROVIDER:jev} # jev | none
    max-others: ${SBA_JUDGE_MAX_OTHERS:8}
    timeout-ms: ${SBA_JUDGE_TIMEOUT_MS:12000}
    beat:
      gap-ms: ${SBA_JUDGE_BEAT_GAP_MS:4000}
      max-events: ${SBA_JUDGE_BEAT_MAX_EVENTS:12}
      max-chars: ${SBA_JUDGE_BEAT_MAX_CHARS:1500}
```

`JevJudge` reads `SBA_JUDGE_API_KEY`, falling back to `TYPESAFE_API_KEY`, uses `jev-latest`, disables
redirects, has no retries, and rejects the whole response if any expected answer is malformed.

## Surfaces

- `GET /api/events/{id}/judgment` returns the persisted judgment for one event, or `404`.
- `GET /api/sessions/{id}/judgments?limit=` returns newest judgments first.
- `/api/status` and `/api/health/judge` include `{enabled, provider, model, calls, failures,
  lastLatencyMs, queued, dropped}`.
- `/api/stream` emits `judgment.appended` with `{eventIds, sessionId, beatId, phase, salience,
  novelty, human, kin, judge, model, version, judgedAt}`.

Stream v2 is additive: `event.appended` now includes `role`, `textPreview`, and `parentSessionId`;
`session.updated` includes `spawnedBy` and `linkTypes`. `GET /api/stream?since=<ISO-8601>` replays
`event.appended` frames oldest-first. `Last-Event-ID` resumes from the cursor
`<observedAt>|<id>` exclusively, with fractional-second timestamps ordered chronologically.

Each connection replays at most 2,000 events. If another page exists, `replay.more` carries the
last delivered cursor and the connection closes before live delivery. Native `EventSource`
reconnects with that last event ID; other consumers must resume explicitly. Live frames arriving
during replay are buffered and event IDs already replayed are deduplicated. A 2,000-frame live
buffer overflow emits `replay.reset` and closes; consumers should refresh their snapshot.

Replay is a best-effort observed-time view, not a durable ingestion journal. An event ingested
later with an observed timestamp behind the last cursor can be missed after reconnect. Judgment,
session, and task frames are live-only. Consumers needing a complete current view must reconcile
against the corresponding HTTP snapshot endpoints.

Persisted `answers_json` includes normalized phase, salience, novelty, human, and kin (mapped to
session IDs), alongside the raw provider answers. This preserves rule-derived values and context
mapping even after other sessions change.

## Cost And Latency

The beat fold keeps the call rate near one Jev request per active session per few seconds instead of
one request per tool event. The orbit measurements that motivated this stage were roughly 400 ms and
about 1-2k input tokens per call, depending on trail and `others` size. Use the counters in
`/api/health/judge` to measure actual local behavior.

## Optional payload inspection telemetry

`SBA_JUDGE_PAYLOAD_TELEMETRY_ENABLED=true` enables private structured log envelopes
from `JevTelemetry`; the default is false. Approve the actual log destinations and
retention before enabling. This switch adds no provider calls or historical replay.

`blackbox.jev.requested` records the exact serialized body string passed to the HTTP
transport, plus SHA-256, original character length, request/session/beat/event IDs,
and truncation/omission flags. State string leaves receive mandatory built-in
credential redaction before serialization, independent of ingestion settings.
Quoted credential keys embedded in text conservatively redact the remainder of
that text leaf. This is best-effort credential filtering, not general removal of
private content. Headers and API keys are never telemetry fields. If the known
provider credential occurs in the body, body logging is omitted.

Bodies are capped at 32,768 Java characters. A truncated value is only a prefix and
may not be valid JSON; the hash describes the complete sent body. No reconstructed
request is substituted. `blackbox.jev.completed` shares the request ID and reports
duration, bounded error category, and normalized judgment fields. It means the
provider response passed validation, not that database persistence succeeded.
Use the event IDs and `/api/sessions/{id}/judgments` to verify persistence separately.
Raw provider responses and exception messages are not logged by this telemetry.
Logging failures do not interrupt classification.

The application does not install an exporter or enforce log retention. Local source
logs, collector buffers, and the destination database can have different retention.
Disabling the setting stops new payload logging; it does not delete existing copies.
