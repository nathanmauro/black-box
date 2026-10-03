package dev.nathan.sbaagentic.recording;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** Shared validation and projection of home and secondary project lanes. */
public final class Lanes {
    private Lanes() {}

    /** Decode lane metadata from persisted JSON or an in-process capture. */
    public static List<LaneListing> read(Object value) {
        if (!(value instanceof List<?> list) || list.isEmpty()) {

            return null;
        }
        List<LaneListing> lanes = new ArrayList<>();
        for (Object item : list) {
            if (item instanceof Map<?, ?> map) {
                Object score = map.get("score");
                if (map.get("project") != null && score instanceof Number number) {
                    lanes.add(new LaneListing(String.valueOf(map.get("project")), number.doubleValue()));
                }
            } else if (item instanceof LaneListing lane) {
                lanes.add(lane);
            }
        }

        return lanes.isEmpty() ? null : List.copyOf(lanes);
    }

    public static List<LaneListing> validate(String project, String repo, List<LaneListing> alsoIn) {
        if (alsoIn == null || alsoIn.isEmpty()) {

            return null;
        }
        if (alsoIn.size() > 20) {
            throw new IllegalArgumentException("alsoIn may contain at most 20 lanes.");
        }
        Set<String> seen = new HashSet<>();
        String home = project == null || project.isBlank() ? repo : project;
        if (home != null && !home.isBlank()) {
            seen.add(key(home));
        }
        List<LaneListing> result = new ArrayList<>();
        for (LaneListing lane : alsoIn) {
            if (lane == null || lane.project() == null || lane.project().isBlank()) {
                throw new IllegalArgumentException("alsoIn project must not be blank.");
            }
            String name = lane.project().strip();
            if (lane.score() == null) {
                throw new IllegalArgumentException("alsoIn score is required for project '" + name + "'.");
            }
            if (!Double.isFinite(lane.score()) || lane.score() < 0 || lane.score() > 1) {
                throw new IllegalArgumentException("alsoIn score for project '" + name
                        + "' must be between 0.0 and 1.0 (got " + lane.score() + ").");
            }
            if (!seen.add(key(name))) {
                throw new IllegalArgumentException(
                        "alsoIn projects must be unique and different from the home lane: " + name);
            }
            result.add(new LaneListing(name, lane.score()));
        }

        return List.copyOf(result);
    }

    /** An older revision's secondary lane cannot also be the chosen home lane in a read view. */
    public static List<LaneListing> withoutHome(String project, List<LaneListing> alsoIn) {
        if (alsoIn == null) {

            return null;
        }
        if (project == null || project.isBlank()) {

            return alsoIn;
        }
        List<LaneListing> remaining = alsoIn.stream()
                .filter(lane -> lane != null
                        && lane.project() != null
                        && !key(lane.project()).equals(key(project)))
                .toList();

        return remaining.isEmpty() ? null : remaining;
    }

    private static String key(String project) {

        return project.strip().toLowerCase(Locale.ROOT);
    }
}
