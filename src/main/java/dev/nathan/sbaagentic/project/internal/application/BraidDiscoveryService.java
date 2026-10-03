package dev.nathan.sbaagentic.project.internal.application;

import dev.nathan.sbaagentic.project.BraidDiscoveryOperations;
import dev.nathan.sbaagentic.project.BraidDiscoveryResult;
import dev.nathan.sbaagentic.project.BraidDiscoveryResult.Hit;
import dev.nathan.sbaagentic.project.BraidDiscoveryResult.Source;
import dev.nathan.sbaagentic.project.internal.application.port.BraidReader;
import dev.nathan.sbaagentic.project.internal.domain.BraidDiscoveryCursor;
import dev.nathan.sbaagentic.project.internal.domain.ProjectKeyCodec;
import dev.nathan.sbaagentic.recording.ExportRedactor;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import org.springframework.stereotype.Service;

@Service
public class BraidDiscoveryService implements BraidDiscoveryOperations {
    private static final String COVERAGE = "saved_braids_all_ownership";
    private static final List<String> NOTES = List.of(
            "Save-time artifacts, not event citations. Provider/model are caller-declared. Text may be transformed or clipped; detailPath addresses stored content.");
    private final BraidReader reader;
    private final ExportRedactor redactor;

    public BraidDiscoveryService(BraidReader reader, ExportRedactor redactor) {
        this.reader = reader;
        this.redactor = redactor;
    }

    @Override
    public BraidDiscoveryResult find(
            String id, String query, String sessionId, Integer limit, String before, Integer maxBytes) {
        int budget = maxBytes == null ? 24000 : maxBytes;
        if (budget < 2048 || budget > 64000)

            return error("invalid_request", 2048, "maxBytes must be between 2048 and 64000.");
        int count = limit == null ? 10 : limit;
        if (count < 1 || count > 20)

            return error("invalid_request", budget, "limit must be between 1 and 20.");

        if (!portableText(id) || !portableText(query) || !portableText(sessionId))

            return error("invalid_request", budget, "Selectors must contain valid Unicode text without U+0000.");
        if (query != null) {
            query = query.strip();
            if (query.isEmpty() || query.length() > 1024)

                return error(
                        "invalid_request",
                        budget,
                        "query must be nonblank and at most 1024 UTF-16 units after trimming.");
        }
        if (id != null && id.isBlank() || sessionId != null && sessionId.isBlank())

            return error(
                    "invalid_request",
                    budget,
                    "id and sessionId must be nonblank exact internal identities when supplied.");
        if (id != null && (query != null || sessionId != null || before != null))

            return error("invalid_request", budget, "id cannot be combined with query, sessionId or before.");
        String fingerprint = BraidDiscoveryCursor.fingerprint(query, sessionId);
        BraidDiscoveryCursor cursor;
        try {
            cursor = BraidDiscoveryCursor.parse(before, fingerprint);
        } catch (IllegalArgumentException ex) {

            return error("invalid_request", budget, ex.getMessage());
        }
        List<BraidReader.Candidate> rows = reader.findBraids(
                id,
                query,
                sessionId,
                id == null ? count + 1 : 1,
                cursor == null ? null : cursor.createdAt(),
                cursor == null ? null : cursor.id());
        if (id != null && rows.isEmpty())

            return error("not_found", budget, "No saved braid has that artifact ID.");

        List<Hit> kept = new ArrayList<>();
        for (int i = 0; i < Math.min(count, rows.size()); i++) {
            Hit full = export(rows.get(i));
            Hit best = full;
            kept.add(best);
            if (BraidDiscoveryJson.bytes(result(kept, rows.size(), id != null, fingerprint, budget)) > budget) {
                best = bodyOnly(full, 0);
                kept.set(i, best);
                boolean bodyFirst =
                        BraidDiscoveryJson.bytes(result(kept, rows.size(), id != null, fingerprint, budget)) <= budget;
                if (!bodyFirst) {
                    best = clip(full, 0);
                    kept.set(i, best);
                }
                if (BraidDiscoveryJson.bytes(result(kept, rows.size(), id != null, fingerprint, budget)) > budget) {
                    kept.removeLast();
                    if (kept.isEmpty())

                        return error(
                                "budget_exceeded",
                                budget,
                                "The first artifact identity and ordered source references cannot fit maxBytes. Increase the budget; no results were advanced.");
                    break;
                }
                int low = 1, high = budget;
                while (low <= high) {
                    int mid = (low + high) / 2;
                    Hit candidate = bodyFirst ? bodyOnly(full, mid) : clip(full, mid);
                    kept.set(i, candidate);
                    if (BraidDiscoveryJson.bytes(result(kept, rows.size(), id != null, fingerprint, budget))
                            <= budget) {
                        best = candidate;
                        low = mid + 1;
                    } else high = mid - 1;
                }
                kept.set(i, best);
            }
        }

        return result(kept, rows.size(), id != null, fingerprint, budget);
    }

    private BraidDiscoveryResult result(
            List<Hit> items, int candidates, boolean exact, String fingerprint, int budget) {
        String next = null;
        if (!exact && !items.isEmpty() && items.size() < candidates) {
            Hit last = items.getLast();
            next = new BraidDiscoveryCursor(Instant.parse(last.createdAt()), last.artifactId(), fingerprint).encoded();
        }

        return new BraidDiscoveryResult(
                "ok",
                COVERAGE,
                items.size(),
                List.copyOf(items),
                next,
                items.stream()
                        .anyMatch(h -> h.titleTruncated()
                                || h.bodyTruncated()
                                || h.provenanceTruncated()
                                || h.referenceOnly()),
                budget,
                NOTES);
    }

    private BraidDiscoveryResult error(String status, int budget, String diagnostic) {

        return new BraidDiscoveryResult(status, COVERAGE, 0, List.of(), null, false, budget, List.of(diagnostic));
    }

    private Hit export(BraidReader.Candidate row) {
        String canonical = redactor.redactForExport(row.canonicalKey());
        String title = redactor.redactForExport(row.title()), body = redactor.redactForExport(row.body());
        String provider = redactor.redactForExport(row.provider()), model = redactor.redactForExport(row.model());
        List<Source> sources = row.sessions().stream()
                .map(s -> {
                    String source = redactor.redactForExport(s.source()),
                            client = redactor.redactForExport(s.clientSessionId()),
                            cwd = redactor.redactForExport(s.cwd());

                    return new Source(
                            s.sessionId(),
                            source,
                            client,
                            cwd,
                            s.provenanceBasis(),
                            changed(source, s.source())
                                    || changed(client, s.clientSessionId())
                                    || changed(cwd, s.cwd()),
                            false);
                })
                .toList();
        boolean ownershipChanged = changed(canonical, row.canonicalKey());

        return new Hit(
                row.id(),
                "braid",
                "saved_meld",
                row.createdAt().toString(),
                row.canonicalKey() == null ? "unassigned" : "project",
                canonical == null || ownershipChanged ? null : ProjectKeyCodec.encode(canonical),
                canonical,
                title,
                body,
                provider,
                model,
                "caller_declared",
                sources,
                "/api/melds/"
                        + URLEncoder.encode(row.id(), StandardCharsets.UTF_8).replace("+", "%20"),
                false,
                false,
                false,
                ownershipChanged
                        || changed(title, row.title())
                        || changed(body, row.body())
                        || changed(provider, row.provider())
                        || changed(model, row.model())
                        || sources.stream().anyMatch(Source::transformed),
                false,
                !(ownershipChanged
                        || changed(title, row.title())
                        || changed(body, row.body())
                        || changed(provider, row.provider())
                        || changed(model, row.model())
                        || sources.stream().anyMatch(Source::transformed)));
    }

    private static Hit bodyOnly(Hit hit, int cap) {
        String body = prefix(hit.body(), cap);
        boolean truncated = changed(body, hit.body());

        return new Hit(
                hit.artifactId(),
                hit.artifactKind(),
                hit.sourceType(),
                hit.createdAt(),
                hit.ownership(),
                hit.projectKey(),
                hit.canonicalKey(),
                hit.title(),
                body,
                hit.provider(),
                hit.model(),
                hit.providerBasis(),
                hit.sessions(),
                hit.detailPath(),
                hit.titleTruncated(),
                truncated,
                hit.provenanceTruncated(),
                hit.transformed(),
                false,
                hit.textComplete() && !truncated);
    }

    private static Hit clip(Hit hit, int cap) {
        String title = prefix(hit.title(), cap),
                body = prefix(hit.body(), cap),
                canonical = prefix(hit.canonicalKey(), cap);
        String provider = prefix(hit.provider(), cap), model = prefix(hit.model(), cap);
        List<Source> sources = hit.sessions().stream()
                .map(s -> {
                    String source = prefix(s.source(), cap),
                            client = prefix(s.clientSessionId(), cap),
                            cwd = prefix(s.cwd(), cap);

                    return new Source(
                            s.sessionId(),
                            source,
                            client,
                            cwd,
                            s.provenanceBasis(),
                            s.transformed(),
                            changed(source, s.source())
                                    || changed(client, s.clientSessionId())
                                    || changed(cwd, s.cwd()));
                })
                .toList();
        boolean provenance = changed(canonical, hit.canonicalKey())
                || changed(provider, hit.provider())
                || changed(model, hit.model())
                || sources.stream().anyMatch(Source::truncated);

        return new Hit(
                hit.artifactId(),
                hit.artifactKind(),
                hit.sourceType(),
                hit.createdAt(),
                hit.ownership(),
                cap == 0 || changed(canonical, hit.canonicalKey()) ? null : hit.projectKey(),
                canonical,
                title,
                body,
                provider,
                model,
                hit.providerBasis(),
                sources,
                hit.detailPath(),
                changed(title, hit.title()),
                changed(body, hit.body()),
                provenance,
                hit.transformed(),
                cap == 0,
                hit.textComplete() && !changed(title, hit.title()) && !changed(body, hit.body()) && !provenance);
    }

    private static String prefix(String value, int cap) {
        if (value == null || value.codePointCount(0, value.length()) <= cap)

            return value;

        return value.substring(0, value.offsetByCodePoints(0, cap));
    }

    private static boolean portableText(String value) {

        return value == null
                || value.indexOf(0) < 0 && StandardCharsets.UTF_8.newEncoder().canEncode(value);
    }

    private static boolean changed(String first, String second) {

        return !Objects.equals(first, second);
    }
}
