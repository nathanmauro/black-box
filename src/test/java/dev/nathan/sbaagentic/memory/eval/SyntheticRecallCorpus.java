package dev.nathan.sbaagentic.memory.eval;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

/**
 * A deterministic multi-project corpus of decisions, handoffs, observations, hard-negative
 * distractors, and braids, plus graded relevance judgments for every benchmark query.
 *
 * <p>Every item has a stable fixture key ({@code project/topic/kind}); judgments reference keys,
 * never database ids, so the same corpus can be seeded into any fresh database. Capture order is
 * shuffled with a fixed seed so recency (the lexical tie-breaker) is uncorrelated with relevance.
 */
final class SyntheticRecallCorpus {

    static final long SEED = 20261119L;

    static final int GRADE_DECISION = 3;
    static final int GRADE_HANDOFF = 2;
    static final int GRADE_OBSERVATION = 1;

    static final List<String> PROJECTS =
            List.of("atlas-api", "beacon-ui", "cobalt-ingest", "delta-billing", "ember-mobile", "fjord-infra");

    record Topic(
            String key,
            String phrase,
            String decision,
            String rationale,
            String handoffSummary,
            String nextAction,
            String observation,
            String paraphrase,
            String searchTerms) {}

    enum Kind {
        DECISION,
        HANDOFF,
        OBSERVATION,
        DISTRACTOR
    }

    /** One captured intent event. {@code topic} is null for distractors. */
    record Item(String key, String project, Topic topic, Kind kind, String text) {

        String repo() {

            return repoFor(project);
        }

        String clientSessionId() {

            return "bench-" + project + "-" + (topic == null ? "misc" : topic.key());
        }
    }

    record Braid(String key, String project, List<Topic> topics, String title, String body) {}

    /** A benchmark query with graded judgments keyed by fixture key; {@code project} is a name. */
    record Query(String id, String suite, String text, String project, Map<String, Integer> grades) {}

    static final List<Topic> TOPICS = List.of(
            new Topic(
                    "sqlite-busy",
                    "busy timeout for sqlite writers",
                    "raise the busy timeout for sqlite writers to five seconds",
                    "Concurrent writers were hitting SQLITE_BUSY lock contention during bulk ingest.",
                    "busy timeout for sqlite writers is raised; contention is gone in the ingest soak.",
                    "Watch the lock contention gauge after the next bulk import.",
                    "saw SQLITE_BUSY lock contention twice under parallel writers during the soak.",
                    "why do concurrent database writes stall",
                    "sqlite busy timeout"),
            new Topic(
                    "cursor-pagination",
                    "cursor pagination for event feeds",
                    "switch to cursor pagination for event feeds",
                    "Offset paging skipped rows and clients only received the first page of results.",
                    "cursor pagination for event feeds ships behind the v2 endpoint.",
                    "Migrate the remaining list endpoints off offset paging.",
                    "the feed client truncated at 250 rows because offset paging stopped early.",
                    "the API only returned the first page of rows",
                    "cursor pagination feeds"),
            new Topic(
                    "key-rotation",
                    "rotate signing keys every thirty days",
                    "rotate signing keys every thirty days",
                    "Long-lived session signing keys widen the blast radius of a leaked credential.",
                    "the job to rotate signing keys every thirty days is scheduled with one overlap window.",
                    "Add an alert when a key passes its rotation date.",
                    "found a session token signed with a key that was ninety days old.",
                    "how often do we change the credentials that sign sessions",
                    "signing keys rotate"),
            new Topic(
                    "webhook-backoff",
                    "exponential backoff with jitter for webhook retries",
                    "use exponential backoff with jitter for webhook retries",
                    "Fixed-interval retries stampeded the receiver after every outage.",
                    "exponential backoff with jitter for webhook retries is live; dead letters go to replay.",
                    "Expose the dead letter queue on the admin screen.",
                    "a failed callback delivery retried forty times in one minute after the outage.",
                    "what happens when a callback delivery fails",
                    "webhook backoff jitter"),
            new Topic(
                    "feature-flag",
                    "gate the rollout behind a feature flag",
                    "gate the rollout behind a feature flag",
                    "A staged release exposes the change to a small cohort and rolls back without a deploy.",
                    "the work to gate the rollout behind a feature flag is wired; it defaults off in production.",
                    "Ramp the cohort to ten percent after a clean day.",
                    "the canary cohort saw no error spike with the toggle on.",
                    "how do we ship the change to a few users",
                    "feature flag rollout"),
            new Topic(
                    "cache-invalidation",
                    "cache invalidation on schema change",
                    "trigger cache invalidation on schema change",
                    "Query results stayed stale after migrations because cached plans outlived the schema.",
                    "cache invalidation on schema change runs from the migration hook.",
                    "Add a test that migrates and asserts fresh results.",
                    "users saw stale results for an hour after the last migration.",
                    "why are query answers outdated once we migrate",
                    "cache invalidation schema"),
            new Topic(
                    "trace-logging",
                    "structured json logging with trace ids",
                    "adopt structured json logging with trace ids",
                    "Grepping free-form logs could not follow one request across services.",
                    "structured json logging with trace ids is on in the gateway and workers.",
                    "Propagate the trace header through the queue consumers.",
                    "one checkout request crossed four services and no log line tied them together.",
                    "how do we correlate log lines across services",
                    "json logging trace"),
            new Topic(
                    "test-isolation",
                    "temp file databases per test class",
                    "give each suite temp file databases per test class",
                    "Shared in-memory databases leaked rows between tests and made failures order dependent.",
                    "temp file databases per test class landed; the flaky ordering failures stopped.",
                    "Delete the old shared fixture loader.",
                    "a test passed alone but failed after another suite wrote rows into the shared database.",
                    "tests interfering with each other's data",
                    "temp file databases"));

    /** Hard negatives: they share surface words with real topics but answer none of the queries. */
    static final List<String> DISTRACTORS = List.of(
            "raise the timeout on the release checklist page review",
            "the signing ceremony for the mobile build moved to Thursdays",
            "rows in the pricing table were reordered for readability",
            "the log viewer theme now matches the dashboard palette",
            "flag emoji support landed in the chat composer",
            "test the cache warmup script on staging hardware",
            "the json schema for the settings export gained a version field",
            "rotate the on-call schedule so nobody covers two weekends");

    /**
     * Concept lexicon for {@link HashingTextEmbedder}: stemmed token to shared concept. It encodes
     * the paraphrase links a real embedding model would learn, and nothing about the fixture keys.
     */
    static final Map<String, String> LEXICON = lexicon();

    private SyntheticRecallCorpus() {}

    static String repoFor(String project) {

        return "/bench/" + project;
    }

    /** Topic coverage varies by project: odd-indexed projects carry all eight topics, even ones four. */
    static boolean projectCarries(int projectIndex, int topicIndex) {

        return (projectIndex + 2 * topicIndex) % 4 != 0;
    }

    /** Intent items in deterministic, seeded capture order. */
    static List<Item> items() {
        List<Item> items = new ArrayList<>();
        for (int p = 0; p < PROJECTS.size(); p++) {
            String project = PROJECTS.get(p);
            for (int t = 0; t < TOPICS.size(); t++) {
                if (!projectCarries(p, t)) {
                    continue;
                }
                Topic topic = TOPICS.get(t);
                items.add(item(project, topic, Kind.DECISION, topic.decision()));
                items.add(item(project, topic, Kind.HANDOFF, topic.handoffSummary()));
                items.add(item(project, topic, Kind.OBSERVATION, topic.observation()));
            }
            for (int d = 0; d < 3; d++) {
                String text = DISTRACTORS.get((p * 3 + d) % DISTRACTORS.size());
                items.add(new Item(
                        project + "/distractor/" + d, project, null, Kind.DISTRACTOR, prefixed(project, text)));
            }
        }
        Collections.shuffle(items, new Random(SEED));

        return List.copyOf(items);
    }

    /** One braid per consecutive pair of topics a project carries. */
    static List<Braid> braids() {
        List<Braid> braids = new ArrayList<>();
        for (int p = 0; p < PROJECTS.size(); p++) {
            String project = PROJECTS.get(p);
            List<Topic> carried = new ArrayList<>();
            for (int t = 0; t < TOPICS.size(); t++) {
                if (projectCarries(p, t)) {
                    carried.add(TOPICS.get(t));
                }
            }
            for (int i = 0; i + 1 < carried.size(); i += 2) {
                Topic first = carried.get(i);
                Topic second = carried.get(i + 1);
                braids.add(new Braid(
                        project + "/braid/" + first.key() + "+" + second.key(),
                        project,
                        List.of(first, second),
                        project + " braid: " + first.key() + " with " + second.key(),
                        "Threads joined: " + first.phrase() + "; " + second.phrase() + "."));
            }
        }

        return List.copyOf(braids);
    }

    static List<Query> queries() {
        List<Item> items = items();
        List<Query> queries = new ArrayList<>();
        for (Topic topic : TOPICS) {
            Map<String, Integer> grades = topicGrades(items, topic, null);
            queries.add(new Query("exact:" + topic.key(), "recall.exact", topic.phrase(), null, grades));
            queries.add(new Query("paraphrase:" + topic.key(), "recall.paraphrase", topic.paraphrase(), null, grades));
            queries.add(new Query("search:" + topic.key(), "search.compact", topic.searchTerms(), null, grades));
            queries.add(new Query("braid:" + topic.key(), "braids.find", topic.phrase(), null, braidGrades(topic)));
        }
        for (int p = 0; p < PROJECTS.size(); p++) {
            String project = PROJECTS.get(p);
            for (int t = 0; t < TOPICS.size(); t++) {
                if (!projectCarries(p, t)) {
                    continue;
                }
                Topic topic = TOPICS.get(t);
                queries.add(new Query(
                        "project:" + project + ":" + topic.key(),
                        "recall.project",
                        topic.paraphrase(),
                        project,
                        topicGrades(items, topic, project)));
            }
        }

        return List.copyOf(queries);
    }

    private static Map<String, Integer> topicGrades(List<Item> items, Topic topic, String project) {
        Map<String, Integer> grades = new LinkedHashMap<>();
        for (Item item : items) {
            if (item.topic() != topic || (project != null && !project.equals(item.project()))) {
                continue;
            }
            grades.put(
                    item.key(),
                    switch (item.kind()) {
                        case DECISION -> GRADE_DECISION;
                        case HANDOFF -> GRADE_HANDOFF;
                        case OBSERVATION -> GRADE_OBSERVATION;
                        case DISTRACTOR -> 0;
                    });
        }

        return Map.copyOf(grades);
    }

    private static Map<String, Integer> braidGrades(Topic topic) {
        Map<String, Integer> grades = new LinkedHashMap<>();
        for (Braid braid : braids()) {
            if (braid.topics().contains(topic)) {
                grades.put(braid.key(), 1);
            }
        }

        return Map.copyOf(grades);
    }

    private static Item item(String project, Topic topic, Kind kind, String text) {
        String key = project + "/" + topic.key() + "/" + kind.name().toLowerCase(java.util.Locale.ROOT);

        return new Item(key, project, topic, kind, prefixed(project, text));
    }

    private static String prefixed(String project, String text) {

        return project + ": " + text;
    }

    private static Map<String, String> lexicon() {
        Map<String, String> lexicon = new LinkedHashMap<>();
        concept(lexicon, "database", "sqlite", "database", "db", "data");
        concept(lexicon, "write", "writer", "write", "wrote", "ingest", "import");
        concept(lexicon, "contention", "busy", "lock", "contention", "stall", "block");
        concept(lexicon, "concurrency", "concurrent", "parallel");
        concept(lexicon, "paging", "cursor", "pagination", "paging", "page", "offset", "truncat", "stopp");
        concept(lexicon, "record", "row", "result", "record", "answer");
        concept(lexicon, "endpoint", "api", "endpoint", "client", "feed");
        concept(lexicon, "rotation", "rotate", "rotation", "change", "often");
        concept(lexicon, "signing", "sign", "signing");
        concept(lexicon, "credential", "key", "credential", "token", "secret");
        concept(lexicon, "session", "session");
        concept(lexicon, "webhook", "webhook", "callback", "delivery", "receiver");
        concept(lexicon, "retry", "retry", "retri", "backoff", "jitter");
        concept(lexicon, "failure", "fail", "failure", "outage", "happen");
        concept(lexicon, "release", "rollout", "ship", "deploy", "release", "ramp");
        concept(lexicon, "flag", "flag", "gate", "toggle");
        concept(lexicon, "audience", "cohort", "user", "canary", "few", "small", "percent");
        concept(lexicon, "cache", "cache", "cach");
        concept(lexicon, "freshness", "invalidation", "stale", "outdat", "fresh");
        concept(lexicon, "schema", "schema", "migration", "migrate", "structure");
        concept(lexicon, "query", "query", "plan");
        concept(lexicon, "logging", "log", "logging", "line", "grepp");
        concept(lexicon, "correlation", "trace", "correlate", "tie", "follow", "together");
        concept(lexicon, "service", "service", "gateway", "worker", "across");
        concept(lexicon, "test", "test", "suite", "fixture", "flaky");
        concept(lexicon, "isolation", "isolation", "interfer", "leak", "shar", "other", "temp");

        return Map.copyOf(lexicon);
    }

    private static void concept(Map<String, String> lexicon, String concept, String... stems) {
        for (String stem : stems) {
            lexicon.put(stem, concept);
        }
    }
}
