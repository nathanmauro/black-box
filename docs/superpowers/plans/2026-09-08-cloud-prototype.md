# Black Box cloud prototype and product reset

Date: 2026-09-08
Status: shared AWS prototype live and verified; final authentication cleanup in verification

## Current outcome

The shared Lightsail container and managed PostgreSQL deployment is live. Browser login/logout,
secure cookies, authenticated HTTP capture/recall, a real MCP recall call, and retained data across
container replacement passed against AWS. A 15-second SSE comment heartbeat resolved the observed
60-second idle disconnect: the updated deployment kept its original stream open throughout a
75-second observation, with no browser reconnect or error. This is measured behavior, not a claim
of indefinite socket survival or production capacity.

A separate native AgentCore/Step Functions synthetic job completed after its launching client
exited. The model returned fenced JSON; a narrowly scoped validator update and validator-only
redrive recovered the original execution without repeating inference or changing its answer.
The failed history and raw output were retained. An independently recalled Black Box handoff
records the outcome. The coordinator performed that ingestion; an automatic workflow-to-Black-Box
completion adapter and real repository-backed coding execution remain future work. The pilot's
execution gates are closed and its result retained.

The three selected Linear issues are published and duplicate-safe repeat publication passed.
Prototype/SDLC work-mode instructions were installed and exercised in native Codex. Local SQLite,
capture routing, the running local JAR, Elasticsearch and the local model remain intact. Cloud and
local stores are separate, and no private transcript corpus was uploaded.

The full integrated suite passed 569 tests with zero failures/errors and two existing native-vector
skips. Frontend verification passed 607 tests; deployment safety passed 31 tests. Live log review
also identified a request-scoped bearer-authentication context issue during asynchronous cleanup;
its focused regression and fix are being verified before the final release checkpoint.

Operator-specific URLs, resource identities, access instructions, proof artifacts, strategic
recommendations, rejected ideas and the interview narrative are retained in the private project
notes. The earlier sections below preserve the investigation sequence and are superseded by this
current outcome where they differ.

## Outcome

Prove that a cloud agent can receive bounded work, recall prior
decisions, execute while the developer's computer is offline, and capture a useful
handoff. Preserve the existing local capture/recall service. Turn accumulated intent
into evidence-backed candidate work that can feed Linear, without building another
general-purpose project board.

## Product hypothesis

Cross-tool memory alone is insufficient differentiation. The experiment is whether
Black Box can turn forgotten intent into a small set of current, actionable,
source-linked proposals, then close the loop from accepted work to verified outcome.
The graph is a view of provenance, alternatives and outcomes, not an independent
graph-database product. Historical work must retain its original dates and verified
completion status; it must not inflate the active backlog or imply work happened now.

## Today's slices

1. Audit implemented behavior, adoption evidence, runner portability and competitors.
2. Prepare a reproducible private AWS deployment with persistent storage, health
   checks, restart behavior and a documented restore path.
3. Exercise cloud capture -> recall -> bounded unattended execution -> handoff using
   isolated fixture data; use an actual model only after its cloud credentials and
   limits are configured.
4. Build the narrowest evidence-to-Linear preview/publish seam with explicit target,
   provenance and duplicate protection. Verify against a fake service first.
5. Provide reusable rapid-prototype / SDLC commands that change process ceremony,
   never verification, authorization or safety. Reuse existing runner modes.
6. Record decisions and parked ideas in Obsidian, leave a Black Box breadcrumb, and
   report what is deployed, what is only tested locally, and what remains unproven.

## Safety and scope

- Initial checkout: clean `main`, commit `87b6a30`.
- Keep the existing local database, hooks, capture routing and launchd process intact.
- Never package over the JAR used by the running local service; build in an isolated
   checkout/output directory.
- AWS identity, region and cost ceiling must be resolved before paid provisioning.
- Start with an empty cloud dataset and explicit test captures. Do not upload the
   private transcript corpus or local credentials as a deployment side effect.
- No anonymous network exposure. Use a private authenticated access boundary for
   the prototype; disable workstation editor actions and optional local dependencies.
- Keep SQLite as the existing local default. Add a PostgreSQL cloud profile for
   managed durable storage while preserving application behavior. Evaluate Turso on
   measured transaction/client compatibility and operational needs, not marketing.
- Keep external publishing and execution credentials separate from untrusted input.
  Linear publishing requires an explicit team and reviewed candidate selection.
- Preserve the existing board/backend until the replacement path is proven. Stop
   new board feature development; do not destructively remove historical state.
- Repository and global instruction changes remain narrow and reviewable. No blanket
   tool updates or new orchestration frameworks just to conduct this review.

## Acceptance evidence

- Targeted checks and the relevant repository suites pass in an isolated checkout.
- AWS app survives process restart and reads persisted captures afterward.
- Cloud-side scheduled execution has a durable outcome independent of this Mac.
- An independent client can recall the cloud handoff using the documented path.
- Linear preview preserves source IDs/dates, excludes unsupported claims, and repeated
   publication cannot silently create duplicates; real publication is separately recorded.
- A fresh agent can use the mode instructions without inventing native commands or
   disabling required gates.

## Initial checkpoint

- Live local health verified: 6,702 sessions and 304,095 events; optional Elasticsearch
  and local AI were reachable. These counts show capture volume, not user value.
- Cloud and Linear credentials/target questions are pending; no AWS resources or
  Linear issues have been created by this task.
- Independent code audit, market/storage research and mode/idea design are underway.

## Revised architecture and current checkpoint

The user challenged a bare-JAR EC2 lift-and-shift: cloud clients can already call a
remote service, so the deployment must provide useful cloud capabilities and a path
to a shared product. That correction supersedes the original single-host target.

- A temporary, private EC2 validation stack was created with CloudFormation, encrypted
  persistent storage, SSM access and an isolated synthetic database. It is not the
  accepted product deployment; additional host-specific agent setup is paused.
- The intended cloud shape is a shared authenticated container service, managed
  persistent database, and separately bounded cloud agent execution. Local clients
  retain their existing service, data and capabilities. No automatic history transfer.
- Account, region and infrastructure budget are supplied by the user. Work targets
  the authorized account in `us-east-2` with a $50/month infrastructure ceiling.
- PostgreSQL compatibility is being implemented and tested in an isolated clone.
  Keep text timestamp fidelity, canonical embeddings and atomic task completion.
  PostgreSQL alone does not permit multiple API replicas: alias-cycle prevention,
  SSE and runner ownership still have process-local assumptions.
- A Turso JDBC execution probe found unsupported transactions and parameterized
  updates in one published driver. Other actual artifacts are being checked before
  recording a final compatibility decision.
- Managed container candidates are being checked for total cost, HTTPS, long-lived
  streams, database reachability, durable redeployment and CloudFormation support.
- Linear preview/publish CLI passes 24 offline end-to-end tests. Three current,
  source-backed actions have been reviewed. A new Linear project awaits a separate
  approval following automatic review; three selected team issues (NAT-5, NAT-6, NAT-7) are now published and read back; exact repeat returned already-published for all three without another create.
- A reusable prototype/SDLC skill has been installed and exercised through native
  Codex discovery. The separate AgentCore task produced a verified in8 investigation
  and local fixture proof; no AgentCore model execution has occurred.

Before replacing the temporary stack, prove authenticated capture/recall from an
independent client, database durability across container replacement, and bounded
cloud execution. Document the actual costs and deliberately retire temporary
compute rather than leaving two permanent deployments running.

Live price verification corrected the documentation assumption: Ohio Lightsail API reports the active `micro_2_0` database bundle as encrypted at $15/month (1 GB RAM, 40 GB disk). Selected base is therefore $31.20/month including the Small container and three secrets. Pin the returned bundle and PostgreSQL 18 blueprint, and verify actual deployed encryption. The temporary EC2 instance was sent a stop request; encrypted disks remain retained.

## Integrated verification checkpoint

The combined authentication/PostgreSQL source passed 564 Java tests across 101 suites, zero failures/errors and two existing native-vector skips, with the real PostgreSQL 18 contracts enabled. The frontend passed 607 tests in the auth slice; packaged Chromium exercised login, New story (spec/task creation), and logout. An independent security review found and verified fixes for logout-lived streams and mutable image tags.

Managed resources are prepared in the existing stack. The actual database uses `micro_2_0`; that deployed bundle matches the live encrypted catalog. Lightsail does not return a database-level `isEncrypted` field. TLS hostname verification passed and plaintext PostgreSQL startup was rejected. No application image has been deployed at this checkpoint.

The owner separately approved the bounded AgentCore pilot after automatic approval review required direct authorization. Its stack creation has started with paid execution disabled pending controls readback. It does not copy Codex credentials or private history. The earlier Linear project creation request remains optional/pending; the three selected team issues are already verified.
