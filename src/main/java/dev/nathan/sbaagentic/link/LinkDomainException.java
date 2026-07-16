package dev.nathan.sbaagentic.link;

public class LinkDomainException extends RuntimeException {

    private final LinkErrorCode code;

    public LinkDomainException(LinkErrorCode code, String message) {
        super(message);
        this.code = code;
    }

    public LinkErrorCode code() {
        return code;
    }
}
