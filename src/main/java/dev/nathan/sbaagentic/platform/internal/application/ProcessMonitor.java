package dev.nathan.sbaagentic.platform.internal.application;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import dev.nathan.sbaagentic.platform.internal.adapter.in.sse.EventBroadcaster;
import dev.nathan.sbaagentic.platform.internal.adapter.in.sse.StreamEvents;
import dev.nathan.sbaagentic.platform.internal.domain.AgentProcess;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

/**
 * Polls the local system for running agent processes and broadcasts changes over SSE.
 * <p>
 * Matches processes by known agent binary names (claude, codex, cursor, raycast). When the set of
 * running agents changes, broadcasts a {@code processes} SSE event. Failures degrade gracefully;
 * the stream stays live.
 */
@Service
public class ProcessMonitor {

    private static final Logger log = LoggerFactory.getLogger(ProcessMonitor.class);

    private static final Pattern PS_LINE_PATTERN = Pattern.compile(
            "^\\s*(\\d+)\\s+(\\d+)\\s+(\\d+)\\s+([\\d.]+)\\s+(\\S+)\\s+(.+)$");

    private static final Set<String> AGENT_BINARIES = Set.of("claude", "codex", "cursor", "raycast");
    private static final long PS_TIMEOUT_SECONDS = 5;

    private final EventBroadcaster broadcaster;
    private List<AgentProcess> lastSnapshot = Collections.emptyList();
    private boolean available = true;

    public ProcessMonitor(EventBroadcaster broadcaster) {
        this.broadcaster = broadcaster;
    }

    /**
     * Poll every second. Only broadcast when the set changes (spec §4.6, §9).
     */
    @Scheduled(fixedRate = 1000, initialDelay = 1000)
    public void poll() {
        List<AgentProcess> current = pollProcesses();
        if (!Objects.equals(current, lastSnapshot)) {
            lastSnapshot = current;
            broadcaster.publishProcesses(new StreamEvents.ProcessesUpdated(current, available));
        }
    }

    /**
     * Return current processes snapshot synchronously for {@code GET /api/processes}.
     */
    public List<AgentProcess> currentProcesses() {
        return lastSnapshot;
    }

    /**
     * Whether process monitoring is available on this platform.
     */
    public boolean isAvailable() {
        return available;
    }

    private List<AgentProcess> pollProcesses() {
        Process process = null;
        try {
            process = new ProcessBuilder("ps", "-eo", "pid,ppid,rss,pcpu,etime,comm")
                    .redirectErrorStream(true)
                    .start();

            List<AgentProcess> processes = new ArrayList<>();
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
                String line;
                // Skip header line
                reader.readLine();
                while ((line = reader.readLine()) != null) {
                    AgentProcess parsed = parsePsLine(line);
                    if (parsed != null) {
                        processes.add(parsed);
                    }
                }
            }

            boolean completed = process.waitFor(PS_TIMEOUT_SECONDS, TimeUnit.SECONDS);
            if (!completed) {
                log.warn("ps command timed out after {} seconds", PS_TIMEOUT_SECONDS);
                process.destroyForcibly();
                if (available) {
                    available = false;
                }
                return Collections.emptyList();
            }

            if (process.exitValue() != 0) {
                log.warn("ps command exited with code {}", process.exitValue());
                if (available) {
                    available = false;
                }
                return Collections.emptyList();
            }

            if (!available) {
                available = true;
                log.info("Process monitoring restored");
            }

            return processes;
        } catch (IOException ex) {
            if (available) {
                log.warn("Process poll failed, degrading to unavailable: {}", ex.getMessage());
                available = false;
            }
            return Collections.emptyList();
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            if (available) {
                log.warn("Process poll interrupted, degrading to unavailable");
                available = false;
            }
            return Collections.emptyList();
        } finally {
            if (process != null && process.isAlive()) {
                process.destroyForcibly();
            }
        }
    }

    /**
     * Parse a single line from {@code ps -eo pid,ppid,rss,pcpu,etime,comm}.
     * <p>
     * Example formats:
     * <pre>
     *   12345   1234  123456  12.3  01:23:45 /path/to/claude
     *   12346   1234  456789  25.1     12:34 codex
     *   12347   1234  789012   5.0 3-04:56:78 /usr/bin/cursor
     * </pre>
     * Package-private for testing.
     *
     * @return AgentProcess if the line matches a known agent, else null
     */
    AgentProcess parsePsLine(String line) {
        Matcher matcher = PS_LINE_PATTERN.matcher(line);
        if (!matcher.matches()) {
            return null;
        }

        try {
            long pid = Long.parseLong(matcher.group(1));
            // ppid = group(2), not used currently
            long rssKb = Long.parseLong(matcher.group(3));
            double cpuPercent = Double.parseDouble(matcher.group(4));
            String elapsed = matcher.group(5);
            String comm = matcher.group(6);

            String agent = identifyAgent(comm);
            if (agent == null) {
                return null;
            }

            return new AgentProcess(pid, agent, cpuPercent, rssKb, elapsed);
        } catch (NumberFormatException ex) {
            return null;
        }
    }

    /**
     * Match a process command to a known agent binary.
     * <p>
     * Matches on basename (final path segment) to avoid substring false positives (e.g.,
     * "cursor" substring matching Cursor IDE helpers, paths containing "cursor"). Known agent
     * binaries: claude, codex, cursor (CLI), raycast.
     * Package-private for testing.
     *
     * @return agent name ("claude", "codex", etc.) or null if not an agent
     */
    String identifyAgent(String comm) {
        // Extract basename: final segment after last / or \
        String basename = comm.substring(Math.max(comm.lastIndexOf('/'), comm.lastIndexOf('\\')) + 1);
        String normalized = basename.toLowerCase();
        
        for (String agent : AGENT_BINARIES) {
            if (normalized.equals(agent) || normalized.startsWith(agent + ".")) {
                return agent;
            }
        }
        return null;
    }
}
