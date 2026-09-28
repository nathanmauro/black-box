package dev.nathan.sbaagentic.recording;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class HumanTurnsTest {

    @Test
    void plainPromptIsKeptVerbatimIncludingLineBreaks() {
        assertThat(HumanTurns.extract("UserPromptSubmit", "  fix the flaky test\n\nthen push it  "))
                .contains("fix the flaky test\n\nthen push it");
    }

    @Test
    void onlyPromptShapedEventTypesCanCarryAHumanTurn() {
        assertThat(HumanTurns.extract("Stop", "fix the flaky test")).isEmpty();
        assertThat(HumanTurns.extract("PostToolUse", "fix the flaky test")).isEmpty();
        assertThat(HumanTurns.extract("user_prompt_submit", "snake case prompt")).contains("snake case prompt");
        assertThat(HumanTurns.extract("beforeSubmitPrompt", "cursor prompt")).contains("cursor prompt");
        assertThat(HumanTurns.extract("QuickNote", "a thought from the launcher"))
                .contains("a thought from the launcher");
        assertThat(HumanTurns.extract("UserPromptSubmit", "   ")).isEmpty();
        assertThat(HumanTurns.extract("UserPromptSubmit", null)).isEmpty();
    }

    @Test
    void backgroundTaskNotificationIsNotAHumanTurn() {
        String notification = """
                <task-notification>
                <task-id>abc123</task-id>
                <status>completed</status>
                <summary>Agent "Trace backend" finished</summary>
                </task-notification>""";

        assertThat(HumanTurns.extract("UserPromptSubmit", notification)).isEmpty();
    }

    @Test
    void relayedAgentAndCrossSessionMessagesAreNotHumanTurns() {
        assertThat(HumanTurns.extract("UserPromptSubmit", "<agent-message from=\"worker\">\nI finished slice 9\n"))
                .isEmpty();
        assertThat(HumanTurns.extract(
                        "UserPromptSubmit",
                        "<cross-session-message from-name=\"other\">\nNathan asked for this\n</cross-session-message>"))
                .isEmpty();
        assertThat(HumanTurns.extract(
                        "UserPromptSubmit", "<heartbeat>\n  <automation_id>nightly</automation_id>\n</heartbeat>"))
                .isEmpty();
    }

    @Test
    void systemReminderIsStrippedAndTheHumanTextKept() {
        String prompt = "<system-reminder>Message sent at Sat 2026-06-13 03:46:05 UTC.</system-reminder>\n"
                + "mobile reseed opus but it didn't actually run";

        assertThat(HumanTurns.extract("UserPromptSubmit", prompt)).contains("mobile reseed opus but it didn't actually run");
    }

    @Test
    void truncatedHarnessBlockWithoutClosingTagIsDropped() {
        assertThat(HumanTurns.extract("UserPromptSubmit", "<system-reminder>\nThe user started this session without"))
                .isEmpty();
    }

    @Test
    void slashCommandIsUnwrappedToWhatTheHumanTyped() {
        String prompt = "<command-message>idea-sync is running…</command-message>\n"
                + "<command-name>/idea-sync</command-name>\n"
                + "<command-args>everything</command-args>";

        assertThat(HumanTurns.extract("UserPromptSubmit", prompt)).contains("/idea-sync everything");
    }

    @Test
    void shellEscapeKeepsTheCommandAndDropsItsOutput() {
        String prompt = "<bash-input>herdr --version</bash-input><bash-stdout>herdr 0.9.1</bash-stdout><bash-stderr></bash-stderr>";

        assertThat(HumanTurns.extract("UserPromptSubmit", prompt)).contains("! herdr --version");
    }

    @Test
    void ideContextIsStripped() {
        String prompt = "<ide_opened_file>The user opened the file /repo/a.ts in the IDE.</ide_opened_file>\n"
                + "why does this loop never end";

        assertThat(HumanTurns.extract("UserPromptSubmit", prompt)).contains("why does this loop never end");
    }

    @Test
    void codexAttachmentHeaderYieldsOnlyTheRequest() {
        String prompt = """
                # Files mentioned by the user:

                ## Photo 1.jpg: /tmp/attachments/1-Photo-1.jpg

                ## My request for Codex:
                what is wrong with this monitor""";

        assertThat(HumanTurns.extract("UserPromptSubmit", prompt)).contains("what is wrong with this monitor");
        assertThat(HumanTurns.extract("UserPromptSubmit", "# Files mentioned by the user:\n\n## a.png: /tmp/a.png"))
                .isEmpty();
    }

    @Test
    void pastedContentIsReplacedWithAMarker() {
        String prompt = "<pasted_content id=\"698a\">\nlong pasted log\n</pasted_content>\nwhat does this error mean";

        assertThat(HumanTurns.extract("UserPromptSubmit", prompt)).contains("[pasted]\nwhat does this error mean");
    }

    @Test
    void realtimeVoiceDelegationYieldsTheSpokenUtterance() {
        String prompt = """
                <realtime_delegation>
                  <input>So if I'm just sitting here idle, that costs?</input>
                  <transcript_delta>assistant:  It does.
                user:  Five cents a minute on my plan?
                user:  So if I'm just sitting here idle, that costs?</transcript_delta>
                </realtime_delegation>""";

        assertThat(HumanTurns.extract("UserPromptSubmit", prompt))
                .contains("So if I'm just sitting here idle, that costs?");
    }

    @Test
    void realtimeTailFlushYieldsTheUserLinesFromTheDelta() {
        String prompt = """
                <realtime_delegation>
                  <source>transcript_tail_flush</source>
                  <input>The user just ended their realtime session. Acknowledge the handoff.</input>
                  <transcript_delta>assistant:  Let me confirm that.
                user:  so the idle time counts too
                assistant:  Yes.
                user:  ok end it</transcript_delta>
                </realt""";

        assertThat(HumanTurns.extract("UserPromptSubmit", prompt)).contains("so the idle time counts too\nok end it");
    }

    @Test
    void realtimeTailFlushWithoutUserLinesIsNotAHumanTurn() {
        String prompt = "<realtime_delegation>\n<source>transcript_tail_flush</source>\n"
                + "<input>The user just ended their realtime session.</input>\n"
                + "<transcript_delta>assistant:  Bye.</transcript_delta>\n</realtime_delegation>";

        assertThat(HumanTurns.extract("UserPromptSubmit", prompt)).isEmpty();
    }

    @Test
    void codexDelegationWrittenByTheVoiceCoordinatorIsNotAHumanTurn() {
        String prompt = "<codex_delegation>\n  <source_thread_id>t-1</source_thread_id>\n"
                + "  <input>Separate task: summarize the repo</input>\n</codex_delegation>";

        assertThat(HumanTurns.extract("UserPromptSubmit", prompt)).isEmpty();
    }

    @Test
    void knownAutomationPromptsAreNotHumanTurns() {
        assertThat(HumanTurns.extract("UserPromptSubmit", "## Memory Writing Agent: Phase 2 (Consolidation)\n..."))
                .isEmpty();
        assertThat(HumanTurns.extract("UserPromptSubmit", "Automation: Standup summary\nRun the standup."))
                .isEmpty();
        assertThat(HumanTurns.extract(
                        "UserPromptSubmit", "# Overview\n\nGenerate 0 to 3 hyperpersonalized suggestions for this user"))
                .isEmpty();
        assertThat(HumanTurns.extract("UserPromptSubmit", "# AGENTS.md instructions for /repo\n\n<INSTRUCTIONS>\nrules\n</INSTRUCTIONS>"))
                .isEmpty();
        assertThat(HumanTurns.extract("UserPromptSubmit", "---\nstory: v1\nrepo: \"/tmp/x\"\n---\n# Story")).isEmpty();
    }

    @Test
    void longSecondPersonBriefIsAnAgentWrittenPromptButAShortRemarkIsHuman() {
        String brief = "You are the sole implementation worker for a narrow fix. " + "Follow the plan exactly. ".repeat(20);

        assertThat(HumanTurns.extract("UserPromptSubmit", brief)).isEmpty();
        assertThat(HumanTurns.extract("UserPromptSubmit", "You are wrong about the port, it's 8766"))
                .contains("You are wrong about the port, it's 8766");
    }

    @Test
    void longPromptOpeningWithAMarkdownHeadingIsAnAgentBriefButAShortOneIsHuman() {
        String brief = "# Task: README rewrite\n\nWorking directory: /repo\n" + "Do the step. ".repeat(30);

        assertThat(HumanTurns.extract("UserPromptSubmit", brief)).isEmpty();
        assertThat(HumanTurns.extract("UserPromptSubmit", "# why is this heading huge")).contains("# why is this heading huge");
        assertThat(HumanTurns.extract("UserPromptSubmit", "PLEASE IMPLEMENT THIS PLAN:\n# Plan\nstep one")).isEmpty();
    }

    @Test
    void excessBlankLinesCollapse() {
        assertThat(HumanTurns.extract("UserPromptSubmit", "first\n\n\n\n\nsecond")).contains("first\n\nsecond");
    }

    @Test
    void stripBoilerplateCleansAnyTextForTitleUse() {
        assertThat(HumanTurns.stripBoilerplate("<system-reminder>\nnoise\n</system-reminder>\nFix the build"))
                .isEqualTo("Fix the build");
        assertThat(HumanTurns.stripBoilerplate("<task-notification>\n<task-id>x</task-id>\n</task-notification>"))
                .isEmpty();
        assertThat(HumanTurns.stripBoilerplate(null)).isEmpty();
    }
}
