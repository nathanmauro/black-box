package dev.nathan.sbaagentic.summary;

/**
 * One chat completion with what callers need to judge it: the trimmed message content (possibly
 * empty), the provider's {@code finish_reason} and the model it reports serving. Either may be null
 * when the server omits it.
 */
public record ModelCompletion(String content, String finishReason, String model) {}
