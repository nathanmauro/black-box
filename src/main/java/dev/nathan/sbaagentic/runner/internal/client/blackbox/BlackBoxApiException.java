package dev.nathan.sbaagentic.runner.internal.client.blackbox;

public class BlackBoxApiException extends RuntimeException {

    private final int statusCode;
    private final String responseBody;

    public BlackBoxApiException(String message, Throwable cause) {
        super(message, cause);
        this.statusCode = -1;
        this.responseBody = null;
    }

    public BlackBoxApiException(String method, String uri, int statusCode, String responseBody) {
        super(method + " " + uri + " returned HTTP " + statusCode + ": " + responseBody);
        this.statusCode = statusCode;
        this.responseBody = responseBody;
    }

    public int statusCode() {
        return statusCode;
    }

    public String responseBody() {
        return responseBody;
    }
}
