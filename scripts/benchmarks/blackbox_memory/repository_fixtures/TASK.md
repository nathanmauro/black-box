# Development fixture: preserve structured capture evidence safely

Continue the existing Java repository's capture-redaction behavior. Default redaction must remove
credential-bearing structured values and credential material in member names, including nested
maps/lists. Changed names must not overwrite ordinary fields. Preserve ordinary evidence, caller
inputs, disabled behavior, and explicitly configured custom-rule behavior.

Only the production RedactionService.java source is writable for this task. The checked-in public
tests describe existing behavior. No new dependency, provider call, service, database, deployment,
or network access is needed. This is an already-published development fixture, not a held-out task
or evidence that any agent benefits from memory.
