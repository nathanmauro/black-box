# Isolate the public demo runtime

The demo promised isolated history and no cloud use, but only overrode four environment variables.
Inherited Spring/JVM configuration could redirect its datasource; the default external summary
backend remained active. Its fixed scratch filename could also be reset by a second demo run.

## Contract

- Each invocation owns a newly created private scratch directory, database, and log.
- Start Java with a minimal environment, classpath-only application configuration, and an explicit
  default profile. Pin loopback binding and disable all model paths, external summaries, and editor
  launches. Preserve the user's Java executable selection.
- Validate the port and confirm the healthy listener belongs to the process this invocation started
  before seeding. Never terminate an unrelated process.
- Verify the captured Decision, Handoff, and open loop before claiming the recall demonstration
  succeeded. Leave the demo process and its own files available for exploration on success.
- Keep the installed service, existing databases, hook settings, and model configuration unchanged.

## Verification

1. Reproduce inherited configuration and fixed-directory behavior by exercising the actual launcher
   with fake Java and disposable paths. The original launcher failed those regression checks.
2. Run offline launch contracts for hostile ambient settings, unique private directories, invalid
   ports, and occupied ports. Integrate these contracts into CI and the local verification gate.
3. Run the actual packaged demo with disposable decoy configuration, prove capture/recall and local
   summary fallback, inspect the database used, then stop only its owned process.

This changes the demonstration harness, not application defaults. Normal application summaries
continue to use their configured backend.

## Results

- Five offline contracts pass, including the startup port race and ignoring curl configuration.
- The packaged CLI flow passed with conflicting Spring/JVM settings, a working-directory config,
  an external-summary sentinel, and a redirecting curl configuration. It captured seven events,
  recalled the two structured intent records, and generated a local compacted-transcript summary.
  The decoy database and provider sentinel stayed absent. Only the owned demo process was stopped.
- The installed service kept the same listener PID. Shell syntax and whitespace checks passed.
