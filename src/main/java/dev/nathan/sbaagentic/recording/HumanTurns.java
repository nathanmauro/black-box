package dev.nathan.sbaagentic.recording;

import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Decides whether a captured prompt event is something the human actually said, and returns that
 * text verbatim with harness boilerplate removed.
 *
 * <p>Prompt hooks fire for far more than human input: background-task notifications, relayed
 * subagent reports, cross-session messages, automation heartbeats, and agent-written briefs for
 * headless sessions all arrive as {@code UserPromptSubmit}, and the stored {@code role} does not
 * separate them. This class is the single rule set for that decision. It is heuristic: it strips a
 * known list of harness blocks and rejects a known list of automation prompt shapes, so an
 * unrecognized automation prompt can still classify as human. Bump {@link #VERSION} whenever the
 * rules change so stored rows are reclassified on the next start.
 */
public final class HumanTurns {

    /** Rule-set version; stored rows classified under an older version are reclassified on start. */
    public static final int VERSION = 1;

    /** Normalized event types (see {@link EventTypes#normalize}) that can carry a human turn. */
    public static final Set<String> PROMPT_EVENT_TYPES =
            Set.of("userpromptsubmit", "beforesubmitprompt", "quicknote", "quickcapture");

    /** Harness blocks removed wholesale, including a block truncated before its closing tag. */
    private static final List<String> STRIPPED_BLOCKS = List.of(
            "system-reminder",
            "task-notification",
            "agent-message",
            "cross-session-message",
            "heartbeat",
            "scheduled-task",
            "github-webhook-activity",
            "codex_delegation",
            "send_user_message_question_reply",
            "launch-selected-element",
            "ide_opened_file",
            "ide_selection",
            "ide_diagnostics",
            "local-command-stdout",
            "local-command-stderr",
            "local-command-caveat",
            "command-message",
            "bash-stdout",
            "bash-stderr",
            "in-app-browser-context",
            "environment_context",
            "user_instructions",
            "INSTRUCTIONS",
            "create-pr-command");

    private static final List<Pattern> STRIPPED_BLOCK_PATTERNS = STRIPPED_BLOCKS.stream()
            .map(tag -> Pattern.compile(
                    "<" + Pattern.quote(tag) + "\\b[^>]*>.*?(?:</" + Pattern.quote(tag) + ">|\\z)", Pattern.DOTALL))
            .toList();

    private static final Pattern PASTED_CONTENT =
            Pattern.compile("<pasted_content\\b[^>]*>.*?(?:</pasted_content>|\\z)", Pattern.DOTALL);

    private static final Pattern COMMAND_NAME =
            Pattern.compile("<command-name>\\s*(.*?)\\s*</command-name>", Pattern.DOTALL);

    private static final Pattern COMMAND_ARGS =
            Pattern.compile("<command-args>\\s*(.*?)\\s*</command-args>", Pattern.DOTALL);

    private static final Pattern BASH_INPUT = Pattern.compile("<bash-input>\\s*(.*?)\\s*</bash-input>", Pattern.DOTALL);

    private static final String CODEX_REQUEST_HEADER = "## My request for Codex:";

    private static final String CODEX_FILES_HEADER = "# Files mentioned by the user:";

    private static final String REALTIME_DELEGATION = "<realtime_delegation>";

    private static final Pattern REALTIME_SOURCE = Pattern.compile("<source>\\s*(.*?)\\s*(?:</source>|\\z)", Pattern.DOTALL);

    private static final Pattern REALTIME_INPUT = Pattern.compile("<input>\\s*(.*?)\\s*(?:</input>|\\z)", Pattern.DOTALL);

    private static final Pattern REALTIME_DELTA =
            Pattern.compile("<transcript_delta>(.*?)(?:</transcript_delta>|\\z)", Pattern.DOTALL);

    /** Prompt shapes written by automations and orchestrating agents, never by the human. */
    private static final List<String> AUTOMATION_PREFIXES = List.of(
            "## Memory Writing Agent",
            "# Overview\n\nGenerate 0 to 3",
            "Generate a title that",
            "You are an expert at upholding safety",
            "You write concise thread",
            "Automation: ",
            "[Role Reminder:",
            "# AGENTS.md instructions",
            "# Augment Agent",
            "MIMIC BUILD BRIEF",
            "Reply with PONG only.",
            "PLEASE IMPLEMENT THIS PLAN:",
            "---\nstory:");

    /**
     * Long second-person briefs ("You are…", "You score…") and long prompts that open with a
     * markdown heading ("# Task: …") are agent-written briefs, not something the human said.
     */
    private static final Pattern AGENT_BRIEF =
            Pattern.compile("^(?:(?:You|Your) (?:are|score|summarize|write|will|must|job|task|role)\\b|#{1,3} )");

    private static final int AGENT_BRIEF_MIN_LENGTH = 300;

    private static final Pattern EXCESS_BLANK_LINES = Pattern.compile("\\n{3,}");

    private HumanTurns() {}

    /** Whether {@code eventType} is a prompt-shaped event that may carry a human turn. */
    public static boolean isPromptEventType(String eventType) {

        return PROMPT_EVENT_TYPES.contains(EventTypes.normalize(eventType));
    }

    /**
     * The human's words in this event, verbatim apart from removed boilerplate, or empty when the
     * event is not a human turn.
     */
    public static Optional<String> extract(String eventType, String text) {
        if (!isPromptEventType(eventType) || text == null || text.isBlank()) {

            return Optional.empty();
        }
        String normalized = text.replace("\r\n", "\n").strip();
        String cleaned = normalized.startsWith(REALTIME_DELEGATION) ? voiceTurn(normalized) : cleanPrompt(normalized);
        if (cleaned.isEmpty() || isAutomationPrompt(cleaned)) {

            return Optional.empty();
        }

        return Optional.of(cleaned);
    }

    /**
     * {@code text} with harness blocks removed and wrapped commands unwrapped, without deciding
     * whether the result is human. Used for title derivation on any event.
     */
    public static String stripBoilerplate(String text) {
        if (text == null) {

            return "";
        }
        String result = text.replace("\r\n", "\n");
        for (Pattern block : STRIPPED_BLOCK_PATTERNS) {
            result = block.matcher(result).replaceAll("");
        }
        result = PASTED_CONTENT.matcher(result).replaceAll("[pasted]");
        result = unwrapCommand(result);
        result = BASH_INPUT.matcher(result).replaceAll(match -> Matcher.quoteReplacement("! " + match.group(1)));

        return tidy(result);
    }

    private static String cleanPrompt(String text) {
        String result = text;
        int request = result.lastIndexOf(CODEX_REQUEST_HEADER);
        if (request >= 0) {
            result = result.substring(request + CODEX_REQUEST_HEADER.length());
        } else if (result.startsWith(CODEX_FILES_HEADER)) {

            return "";
        }

        return stripBoilerplate(result);
    }

    /**
     * Realtime voice delegations carry the user's utterance in {@code <input>}. A transcript-tail
     * flush carries a machine instruction there instead, so the user's lines come from the
     * transcript delta.
     */
    private static String voiceTurn(String text) {
        Matcher source = REALTIME_SOURCE.matcher(text);
        boolean tailFlush = source.find() && source.group(1).equals("transcript_tail_flush");
        if (tailFlush) {
            Matcher delta = REALTIME_DELTA.matcher(text);
            if (!delta.find()) {

                return "";
            }

            return delta.group(1)
                    .lines()
                    .map(String::strip)
                    .filter(line -> line.startsWith("user:"))
                    .map(line -> line.substring("user:".length()).strip())
                    .filter(line -> !line.isEmpty())
                    .distinct()
                    .collect(Collectors.joining("\n"));
        }
        Matcher input = REALTIME_INPUT.matcher(text);

        return input.find() ? tidy(input.group(1)) : "";
    }

    private static String unwrapCommand(String text) {
        Matcher name = COMMAND_NAME.matcher(text);
        if (!name.find()) {

            return text;
        }
        Matcher args = COMMAND_ARGS.matcher(text);
        String command = args.find() && !args.group(1).isBlank() ? name.group(1) + " " + args.group(1) : name.group(1);
        String rest = COMMAND_ARGS.matcher(COMMAND_NAME.matcher(text).replaceAll("")).replaceAll("");

        return command + "\n" + rest;
    }

    private static boolean isAutomationPrompt(String cleaned) {
        for (String prefix : AUTOMATION_PREFIXES) {
            if (cleaned.startsWith(prefix)) {

                return true;
            }
        }

        return cleaned.length() >= AGENT_BRIEF_MIN_LENGTH && AGENT_BRIEF.matcher(cleaned).find();
    }

    private static String tidy(String text) {

        return EXCESS_BLANK_LINES.matcher(text.strip()).replaceAll("\n\n");
    }
}
