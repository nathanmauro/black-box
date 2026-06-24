package dev.nathan.sbaagentic.web;

import org.junit.jupiter.api.Test;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.context.request.async.AsyncRequestNotUsableException;

import static org.assertj.core.api.Assertions.assertThat;

class ApiExceptionHandlerTest {

    @Test
    void disconnectedEventStreamClientsDoNotUseJsonErrorEnvelope() {
        ApiExceptionHandler handler = new ApiExceptionHandler();

        ResponseEntity<Void> response = handler.handleDisconnectedClient(
                new AsyncRequestNotUsableException("Servlet container error notification for disconnected client"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        assertThat(response.getBody()).isNull();
        assertThat(response.getHeaders().getContentType()).isNull();
    }
}
