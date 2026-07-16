package dev.nathan.sbaagentic.runner.gate;

public record StoryFrontmatter(
        String storyVersion,
        String repo,
        String mode,
        String verify,
        Boolean push,
        Integer priority) {
}
