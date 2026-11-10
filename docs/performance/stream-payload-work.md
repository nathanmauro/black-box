# Stream payload work

The recorder commits before broadcasting optional live updates. Captures with no stream subscribers
previously still built both notification payloads, including two lineage reads for an ordinary
session. Durable subscribers also discarded the eager event payload and rebuilt it from committed
replay rows.

Payload suppliers now run only when the broadcaster needs their value, at most once per publication.
Durable event delivery continues to drain committed rows in order. A needed session update reads
lineage once and reuses that result for both the parent fallback and link types. No stored events,
stream cursors, authorization checks, or replay behavior change.

## Reproducible work counts

`StreamPayloadWorkTest` exercises the real listener, broadcaster, and payload factory with mocked
catalog and lineage boundaries. Its four expectations failed before this change and pass afterward:

| Scenario                              | Before                                              | After                           |
| ------------------------------------- | --------------------------------------------------- | ------------------------------- |
| 1,000 captures with no subscribers    | 2,000 payload constructions and 2,000 lineage reads | 0 of either                     |
| Capture with only durable subscribers | 1 unused eager event payload                        | 0 unused eager event payloads   |
| Session update with parent fallback   | 2 lineage reads                                     | 1 lineage read                  |
| Two transient subscribers             | 1 construction per payload type                     | 1 construction per payload type |

These are deterministic work counts, not an end-to-end latency claim. Durable replay retains its
own payload and lineage work for rows actually delivered. Run with Java 21:

```bash
mvn -Dtest=StreamPayloadWorkTest,EventStreamTest,StreamReplayOrderingTest,StreamShutdownTest,StreamAuthorizationTest,JudgmentStreamPayloadTest,AuthenticationHttpTest test
```

The HTTP tests exercise actual streaming connections against isolated databases, including replay,
heartbeat, logout, and shutdown behavior. No live service or history is required.
