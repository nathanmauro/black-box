package dev.nathan.sbaagentic.runner;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BiConsumer;
import java.util.function.Supplier;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class SseStreamReader {

    private static final Logger log = LoggerFactory.getLogger(SseStreamReader.class);
    private static final long RECONNECT_BACKOFF_MILLIS = 2_000;

    private final Supplier<InputStream> streamSupplier;
    private final BiConsumer<String, String> callback;
    private final AtomicBoolean running = new AtomicBoolean();

    private volatile Thread thread;
    private volatile InputStream currentStream;

    public SseStreamReader(BlackBoxApiClient apiClient, BiConsumer<String, String> callback) {
        this(apiClient::openEventStream, callback);
    }

    public SseStreamReader(Supplier<InputStream> streamSupplier, BiConsumer<String, String> callback) {
        this.streamSupplier = streamSupplier;
        this.callback = callback;
    }

    public void start() {
        if (!running.compareAndSet(false, true)) {
            return;
        }
        Thread readerThread = new Thread(this::readLoop, "blackbox-runner-sse");
        readerThread.setDaemon(true);
        thread = readerThread;
        readerThread.start();
    }

    public void stop() {
        running.set(false);
        closeCurrentStream();
        Thread readerThread = thread;
        if (readerThread != null) {
            readerThread.interrupt();
        }
    }

    private void readLoop() {
        while (running.get()) {
            try (InputStream input = streamSupplier.get();
                    BufferedReader reader = new BufferedReader(
                            new InputStreamReader(input, StandardCharsets.UTF_8))) {
                currentStream = input;
                readFrames(reader);
            }
            catch (IOException | RuntimeException ex) {
                if (running.get()) {
                    log.debug("Black Box SSE stream disconnected; reconnecting", ex);
                }
            }
            finally {
                currentStream = null;
            }
            backoff();
        }
    }

    private void readFrames(BufferedReader reader) throws IOException {
        String eventName = null;
        StringBuilder data = new StringBuilder();
        String line;
        while (running.get() && (line = reader.readLine()) != null) {
            if (line.isEmpty()) {
                dispatch(eventName, data);
                eventName = null;
                data.setLength(0);
            }
            else if (line.startsWith("event:")) {
                eventName = fieldValue(line, "event:");
            }
            else if (line.startsWith("data:")) {
                if (!data.isEmpty()) {
                    data.append('\n');
                }
                data.append(fieldValue(line, "data:"));
            }
        }
        dispatch(eventName, data);
    }

    private void dispatch(String eventName, StringBuilder data) {
        if (eventName == null && data.isEmpty()) {
            return;
        }
        try {
            callback.accept(eventName == null ? "message" : eventName, data.toString());
        }
        catch (RuntimeException ex) {
            log.warn("Black Box SSE callback failed for event {}", eventName, ex);
        }
    }

    private void backoff() {
        if (!running.get()) {
            return;
        }
        try {
            Thread.sleep(RECONNECT_BACKOFF_MILLIS);
        }
        catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
        }
    }

    private void closeCurrentStream() {
        InputStream input = currentStream;
        if (input == null) {
            return;
        }
        try {
            input.close();
        }
        catch (IOException ignored) {
            // Closing is only used to unblock the daemon reader during shutdown.
        }
    }

    private static String fieldValue(String line, String prefix) {
        String value = line.substring(prefix.length());
        return value.startsWith(" ") ? value.substring(1) : value;
    }
}
