package dev.nathan.sbaagentic.runner.process;

import java.io.File;

public interface TmuxController {

    boolean hasSession(String sessionName);

    void killSession(String sessionName);

    void newSession(String sessionName, File cwd, int width, int height);

    void sendKeys(String sessionName, String text);

    String capturePane(String sessionName);
}
