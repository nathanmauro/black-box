package dev.nathan.sbaagentic.platform.internal.adapter.in.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import jakarta.servlet.http.HttpServletResponse;
import java.io.EOFException;
import java.io.IOException;
import org.apache.catalina.connector.ClientAbortException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotWritableException;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.context.request.async.AsyncRequestNotUsableException;
import org.springframework.web.servlet.mvc.method.annotation.ExceptionHandlerExceptionResolver;

class ApiExceptionHandlerTest {

    private static final String FONT = "font/woff2";

    private final MockMvc mockMvc = MockMvcBuilders.standaloneSetup(new FailingFontController())
            .setControllerAdvice(new ApiExceptionHandler())
            .build();
    private final Logger handlerLog = (Logger) LoggerFactory.getLogger(ApiExceptionHandler.class);
    private final Logger resolverLog = (Logger) LoggerFactory.getLogger(ExceptionHandlerExceptionResolver.class);
    private final ListAppender<ILoggingEvent> logs = new ListAppender<>();
    private final ListAppender<ILoggingEvent> resolverLogs = new ListAppender<>();
    private Level originalLevel;

    @BeforeEach
    void captureHandlerLogs() {
        originalLevel = handlerLog.getLevel();
        handlerLog.setLevel(Level.DEBUG);
        logs.start();
        handlerLog.addAppender(logs);
        resolverLogs.start();
        resolverLog.addAppender(resolverLogs);
    }

    @AfterEach
    void releaseHandlerLogs() {
        handlerLog.detachAppender(logs);
        resolverLog.detachAppender(resolverLogs);
        handlerLog.setLevel(originalLevel);
    }

    @Test
    void disconnectedEventStreamClientsDoNotUseJsonErrorEnvelope() {
        ApiExceptionHandler handler = new ApiExceptionHandler();

        ResponseEntity<Void> response = handler.handleDisconnectedClient(
                new AsyncRequestNotUsableException("Servlet container error notification for disconnected client"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        assertThat(response.getBody()).isNull();
        assertThat(response.getHeaders().getContentType()).isNull();
    }

    @Test
    void clientAbortDuringFontResponseIsQuietAndBodyless() throws Exception {
        MvcResult result = mockMvc.perform(get("/fixture/abort")).andReturn();

        assertDisconnectHandled(result, ClientAbortException.class);
    }

    @Test
    void converterFailureCausedByClientAbortIsQuietAndBodyless() throws Exception {
        MvcResult result = mockMvc.perform(get("/fixture/wrapped-abort")).andReturn();

        assertDisconnectHandled(result, HttpMessageNotWritableException.class);
    }

    @Test
    void plainBrokenPipeIOExceptionRemainsAnInternalError() throws Exception {
        assertInternalError("/fixture/plain-broken-pipe", IOException.class);
    }

    @Test
    void eofExceptionRemainsAnInternalError() throws Exception {
        assertInternalError("/fixture/eof", EOFException.class);
    }

    @Test
    void runtimeWrappingConnectionResetRemainsAnInternalError() throws Exception {
        assertInternalError("/fixture/wrapped-reset", IllegalStateException.class);
    }

    @Test
    void unrelatedConverterFailureRemainsAnInternalError() throws Exception {
        assertInternalError("/fixture/unwritable", HttpMessageNotWritableException.class);
    }

    @Test
    void cyclicCauseChainTerminatesAsAnInternalError() {
        // Called directly: Spring's own handler-method lookup recurses through causes and cannot
        // dispatch a cyclic chain, but the handler's traversal must still terminate on one.
        IllegalStateException first = new IllegalStateException("first");
        IllegalStateException second = new IllegalStateException("second", first);
        first.initCause(second);

        ResponseEntity<ApiExceptionHandler.ApiError> response = new ApiExceptionHandler().handleUnexpected(first);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().error().type()).isEqualTo("internal_error");
        assertThat(logs.list)
                .singleElement()
                .satisfies(event -> assertThat(event.getLevel()).isEqualTo(Level.ERROR));
    }

    private void assertDisconnectHandled(MvcResult result, Class<? extends Exception> expected) throws Exception {
        assertThat(result.getResolvedException()).isInstanceOf(expected);
        assertThat(result.getResponse().getContentAsByteArray()).isEmpty();
        assertThat(result.getResponse().getContentType()).isEqualTo(FONT);
        assertThat(resolverLogs.list).as("secondary @ExceptionHandler failure").isEmpty();
        assertThat(logs.list).noneMatch(event -> event.getLevel().isGreaterOrEqual(Level.WARN));
        assertThat(logs.list).singleElement().satisfies(event -> {
            assertThat(event.getLevel()).isEqualTo(Level.DEBUG);
            assertThat(event.getFormattedMessage()).contains("disconnected");
        });
    }

    private void assertInternalError(String path, Class<? extends Exception> expected) throws Exception {
        MvcResult result = mockMvc.perform(get(path)).andReturn();

        assertThat(result.getResolvedException()).isInstanceOf(expected);
        assertThat(result.getResponse().getStatus()).isEqualTo(500);
        assertThat(result.getResponse().getContentType()).isEqualTo("application/json");
        assertThat(resolverLogs.list).as("secondary @ExceptionHandler failure").isEmpty();
        assertThat(result.getResponse().getContentAsString())
                .isEqualTo("{\"error\":{\"status\":500,\"type\":\"internal_error\","
                        + "\"message\":\"The recorder hit an unexpected error handling this request.\"}}");
        assertThat(logs.list).singleElement().satisfies(event -> {
            assertThat(event.getLevel()).isEqualTo(Level.ERROR);
            assertThat(event.getFormattedMessage()).isEqualTo("Unhandled API exception");
        });
    }

    @RestController
    static class FailingFontController {

        @GetMapping("/fixture/abort")
        void abort(HttpServletResponse response) throws IOException {
            response.setContentType(FONT);
            throw new ClientAbortException(new IOException("Broken pipe"));
        }

        @GetMapping("/fixture/wrapped-abort")
        void wrappedAbort(HttpServletResponse response) {
            response.setContentType(FONT);
            throw new HttpMessageNotWritableException(
                    "Could not write font", new ClientAbortException(new IOException("Broken pipe")));
        }

        @GetMapping("/fixture/plain-broken-pipe")
        void plainBrokenPipe() throws IOException {
            throw new IOException("Broken pipe");
        }

        @GetMapping("/fixture/eof")
        void eof() throws IOException {
            throw new EOFException("Unexpected end of stream");
        }

        @GetMapping("/fixture/wrapped-reset")
        void wrappedReset() {
            throw new IllegalStateException("Subprocess failed", new IOException("Connection reset by peer"));
        }

        @GetMapping("/fixture/unwritable")
        void unwritable() {
            throw new HttpMessageNotWritableException("No converter for fixture payload");
        }
    }
}
