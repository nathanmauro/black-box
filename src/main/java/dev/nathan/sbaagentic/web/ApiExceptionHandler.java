package dev.nathan.sbaagentic.web;

import java.util.Locale;
import java.util.stream.Collectors;

import dev.nathan.sbaagentic.link.LinkDomainException;
import dev.nathan.sbaagentic.task.TaskDomainException;
import dev.nathan.sbaagentic.task.TaskErrorCode;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.async.AsyncRequestNotUsableException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
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

    @ExceptionHandler(TaskDomainException.class)
    public ResponseEntity<ApiError> handleTaskDomain(TaskDomainException ex) {
        HttpStatus status = switch (ex.code()) {
            case VALIDATION_FAILED -> HttpStatus.BAD_REQUEST;
            case SPEC_NOT_FOUND, TASK_NOT_FOUND -> HttpStatus.NOT_FOUND;
            case INVALID_TRANSITION, CLAIMANT_MISMATCH, CONCURRENT_MODIFICATION -> HttpStatus.CONFLICT;
            case HANDOFF_FAILED -> HttpStatus.BAD_GATEWAY;
        };
        String type = ex.code().name().toLowerCase(Locale.ROOT);
        return ResponseEntity.status(status).body(ApiError.of(status, type, ex.getMessage()));
    }

    @ExceptionHandler(LinkDomainException.class)
    public ResponseEntity<ApiError> handleLinkDomain(LinkDomainException ex) {
        HttpStatus status = switch (ex.code()) {
            case VALIDATION_FAILED -> HttpStatus.BAD_REQUEST;
            case DUPLICATE_LINK -> HttpStatus.CONFLICT;
        };
        String type = ex.code().name().toLowerCase(Locale.ROOT);
        return ResponseEntity.status(status).body(ApiError.of(status, type, ex.getMessage()));
    }

    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    public ResponseEntity<ApiError> handleMethodNotSupported(HttpRequestMethodNotSupportedException ex) {
        String method = ex.getMethod() == null ? "The requested method" : "Method " + ex.getMethod();
        return ResponseEntity.status(HttpStatus.METHOD_NOT_ALLOWED)
                .body(ApiError.of(HttpStatus.METHOD_NOT_ALLOWED, "method_not_allowed",
                        method + " is not supported for this endpoint."));
    }

    @ExceptionHandler(MissingServletRequestParameterException.class)
    public ResponseEntity<ApiError> handleMissingParameter(MissingServletRequestParameterException ex) {
        return ResponseEntity.badRequest().body(ApiError.of(HttpStatus.BAD_REQUEST, "missing_parameter",
                "Missing required query parameter: " + ex.getParameterName()));
    }

    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<ApiError> handleTypeMismatch(MethodArgumentTypeMismatchException ex) {
        String name = ex.getName() == null ? "request value" : ex.getName();
        return ResponseEntity.badRequest().body(ApiError.of(HttpStatus.BAD_REQUEST, "invalid_argument",
                "Invalid value for " + name + "."));
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ApiError> handleUnreadableMessage(HttpMessageNotReadableException ex) {
        return ResponseEntity.badRequest().body(ApiError.of(HttpStatus.BAD_REQUEST, "malformed_json",
                "Malformed JSON request body."));
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

    @ExceptionHandler(AsyncRequestNotUsableException.class)
    public ResponseEntity<Void> handleDisconnectedClient(AsyncRequestNotUsableException ex) {
        // SSE clients disconnect normally during navigation, refresh, and browser shutdown. At that
        // point the response is already an event stream, so returning the JSON error envelope would
        // trigger a secondary 500 while trying to write the handler response.
        log.debug("Streaming client disconnected before the response completed.", ex);
        return ResponseEntity.noContent().build();
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
