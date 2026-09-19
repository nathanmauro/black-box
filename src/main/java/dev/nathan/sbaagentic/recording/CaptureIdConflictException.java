package dev.nathan.sbaagentic.recording;

/** A client reused one capture identity for a different original event. */
public class CaptureIdConflictException extends RuntimeException {
    public CaptureIdConflictException() {
        super("The capture ID is already bound to a different event payload in this source and client session.");
    }
}
