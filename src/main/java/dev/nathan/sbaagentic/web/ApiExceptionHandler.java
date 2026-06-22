package dev.nathan.sbaagentic.web;

import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.resource.NoResourceFoundException;
import org.springframework.web.server.ResponseStatusException;

/**
 * Turns every API failure into a small, typed JSON envelope instead of a raw stack trace. This
 * matters because the API is consumed by agents: a 500 page or a leaked exception trace would land
 * in an agent's context window as noise. A clean {@code {"error": {...}}} body keeps failures legible
 * to both humans and machines.
 */
@RestControllerAdvice
public class ApiExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(ApiExceptionHandler.class);

    /** A typed API error. {@code error} is always present so callers can branch on it unambiguously. */
    public record ApiError(ErrorBody error) {
        public record ErrorBody(int status, String type, String message) {
        }

        static ApiError of(HttpStatus status, String type, String message) {
            return new ApiError(new ErrorBody(status.value(), type, message));
        }
    }

    @ExceptionHandler(ResponseStatusException.class)
    public ResponseEntity<ApiError> handleStatus(ResponseStatusException ex) {
        HttpStatusCode code = ex.getStatusCode();
        HttpStatus status = HttpStatus.resolve(code.value());
        if (status == null) {
            status = HttpStatus.INTERNAL_SERVER_ERROR;
        }
        String message = ex.getReason() == null ? status.getReasonPhrase() : ex.getReason();
        return ResponseEntity.status(status).body(ApiError.of(status, "request_failed", message));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiError> handleValidation(MethodArgumentNotValidException ex) {
        String message = ex.getBindingResult().getFieldErrors().stream()
                .map(error -> error.getField() + " " + error.getDefaultMessage())
                .collect(Collectors.joining("; "));
        if (message.isBlank()) {
            message = "Request validation failed.";
        }
        return ResponseEntity.badRequest().body(ApiError.of(HttpStatus.BAD_REQUEST, "validation_failed", message));
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<ApiError> handleIllegalArgument(IllegalArgumentException ex) {
        String message = ex.getMessage() == null ? "Invalid request." : ex.getMessage();
        return ResponseEntity.badRequest().body(ApiError.of(HttpStatus.BAD_REQUEST, "invalid_argument", message));
    }

    @ExceptionHandler(NoResourceFoundException.class)
    public ResponseEntity<ApiError> handleMissingResource(NoResourceFoundException ex) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(ApiError.of(HttpStatus.NOT_FOUND, "not_found", "Resource not found."));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiError> handleUnexpected(Exception ex) {
        // Deliberately generic: the detail is logged server-side, never returned to the caller.
        log.error("Unhandled API exception", ex);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(ApiError.of(HttpStatus.INTERNAL_SERVER_ERROR, "internal_error",
                        "The recorder hit an unexpected error handling this request."));
    }
}
