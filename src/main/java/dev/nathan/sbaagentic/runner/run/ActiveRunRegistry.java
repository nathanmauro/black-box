package dev.nathan.sbaagentic.runner.run;

import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.stereotype.Component;

@Component
public class ActiveRunRegistry {

    private final ConcurrentHashMap<String, String> activeTaskToTmuxSession = new ConcurrentHashMap<>();

    public void register(String taskId, String tmuxSessionName) {
        activeTaskToTmuxSession.put(taskId, tmuxSessionName);
    }

    public Optional<String> tmuxSessionFor(String taskId) {
        return Optional.ofNullable(activeTaskToTmuxSession.get(taskId));
    }

    public void deregister(String taskId, String tmuxSessionName) {
        activeTaskToTmuxSession.remove(taskId, tmuxSessionName);
    }
}
