package dev.nathan.sbaagentic.memory;

/** A raw model reply for validation, or a fixed failure reason when no usable reply exists. */
public record ContinuationModelReply(String content, String finishReason, String model, String failureReason) {

    public static ContinuationModelReply reply(String content, String finishReason, String model) {

        return new ContinuationModelReply(content, finishReason, model, null);
    }

    public static ContinuationModelReply failure(String reason) {

        return new ContinuationModelReply(null, null, null, reason);
    }

    public boolean failed() {

        return failureReason != null;
    }
}
