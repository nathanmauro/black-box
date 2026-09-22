package dev.nathan.sbaagentic.judgment.internal.adapter.out.http;

import java.io.IOException;
import java.time.Duration;

public interface JevTransport {

    String post(String endpoint, String apiKey, String body, Duration timeout)
            throws IOException, InterruptedException;
}
