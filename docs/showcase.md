# Showcase assets

The README media in `docs/assets/` is generated from the real product, not mocked up. Each image
is a screenshot of the unmodified UI served by a packaged jar. Each image reads a synthetic story
that was recorded through the public capture API into a throwaway database. The terminal GIF is a
recording of `scripts/demo.sh` running the decision → handoff → recall loop end to end.

| Asset | Shows | Produced by |
| --- | --- | --- |
| `demo.gif` | A Codex session commits a decision and handoff; a fresh Claude session recalls them, open loop included | `scripts/record-demo.sh` |
| `recall.png` | Recall with a project picked, the separate question `revoke-on-logout`, the briefing, and typed Handoff/Decision results | `frontend/tests/e2e/showcase.spec.ts` |
| `hero.png` | Activity scoped to the project: sessions with Decision, Handoff, and Observation landmarks and folded tool rows | `frontend/tests/e2e/showcase.spec.ts` |
| `trajectory.png` | The project trajectory across four epochs, with the head node selected and its evidence in the detail rail | `frontend/tests/e2e/showcase.spec.ts` |

## The synthetic story

Both generators use the fictional project `/tmp/acme-auth`, with records written only to an
isolated database that is deleted afterwards. The terminal demo simulates two agent sessions
through API requests; it does not launch an agent or model. The screenshots extend that example
with earlier history, a completed logout observation, and projected futures:

- Sixteen days ago, a Codex session decides to terminate auth at the API gateway.
- Eleven days ago, a Claude session decides to store hashed refresh tokens in a SQLite rotation
  table and records a load-test observation.
- Six days ago, a Codex handoff says the login and refresh endpoints shipped behind a flag.
- Today, Codex records the JWT decision and a handoff whose open loop is `revoke-on-logout`.
  A fresh Claude session then wires it and observes the result. Three projected futures fan out
  ahead of the head node.

The screenshot spec backdates the earlier epochs with `observedAt`, so the trajectory shows
separate bursts. The decision and handoff that Recall answers from are captured through
`POST /api/decisions` and `POST /api/handoffs`, the same endpoints agents use. Dates in the
images are relative to the day they were generated.

## Regenerate

Prerequisites: Java 21, Maven, Node with the frontend dependencies installed, a Playwright
Chromium, and (for the GIF) `asciinema` and `agg`.

```bash
# Screenshots: builds the jar, starts it on the isolated fixture port 8799 with a temporary
# SQLite database and HOME, seeds the story, drives Recall, Activity, and Trajectory.
cd frontend
SBA_SHOWCASE_OUT="$PWD/../docs/assets" npx playwright test tests/e2e/showcase.spec.ts

# Terminal GIF: records scripts/demo.sh on port 18888 (override with SBA_DEMO_PORT) with a
# private HOME and TMPDIR, then stops only the recorder it started.
cd ..
./scripts/record-demo.sh
```

Without `SBA_SHOWCASE_OUT`, the showcase spec is skipped, so `npm run e2e` never rewrites these
files. `record-demo.sh` refuses a busy port instead of touching another listener. It also refuses
to convert a recording that lacks the recall proof or contains the recording user's home path.

`SBA_DEMO_PACE` (default `1` when recording, `0` for a normal `demo.sh` run) scales the pauses
that `demo.sh` holds on its important output. With pace 1, the GIF holds about 2.5 s on the
"loop just closed" banner and 5 s on the recalled decision and handoff. It then ends on a 6 s
frame with the instructions for exploring the UI. `agg` trims only the idle time above
`5 × pace + 1` seconds, which affects the JVM startup wait and not the scripted holds.

## Verify

The screenshot run is also a test. It fails unless the picked project and question return the
seeded Handoff and Decision. The recalled handoff's Browse link must open the exact captured
session and event. The trajectory must show a head node, projected futures, and a detail panel
with a link back to the stream.

```bash
python3 -m unittest discover -s scripts/demo -p 'test_*.py'  # launcher and recorder isolation
cd frontend
SBA_SHOWCASE_OUT="$(mktemp -d)" npx playwright test tests/e2e/showcase.spec.ts tests/e2e/smoke.spec.ts \
  tests/e2e/continuity.spec.ts tests/e2e/evidence-recall.spec.ts
```

To check the GIF pacing, read the frame delays from the file. `ffmpeg -i docs/assets/demo.gif
frames/f%02d.png` extracts the frames for review. Look at the frames before committing new
media.

The assets contain only synthetic data, loopback URLs, and run-specific `/tmp` paths. They contain
no real history or workstation paths, and no provider or model call is made while generating them.
The recorder preserves its temporary directory and exits with an error if it cannot confirm that
its process stopped; it never kills a different listener to complete cleanup.
