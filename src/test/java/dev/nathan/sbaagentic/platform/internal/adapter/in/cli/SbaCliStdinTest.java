package dev.nathan.sbaagentic.platform.internal.adapter.in.cli;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.DefaultApplicationArguments;

class SbaCliStdinTest {
    private final InputStream unread = new InputStream() {
        @Override
        public int read() {
            throw new AssertionError("stdin must not be read");
        }
    };

    @ParameterizedTest
    @ValueSource(strings = {"", " \n ", "explicit 🐈 text", "--help"})
    void explicitTextIsAuthoritativeEvenWhenEmpty(String text) throws Exception {
        assertThat(SbaCli.ingestText(new DefaultApplicationArguments("ingest", "--text=" + text), unread, false))
                .isEqualTo(text);
    }

    @Test
    void valuelessTextErrorsWithoutReading() {
        assertThatThrownBy(() -> SbaCli.ingestText(new DefaultApplicationArguments("ingest", "--text"), unread, false))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("--text requires a value");
    }

    @Test
    void attachedConsoleRetainsNonreadingNoTextBehavior() throws Exception {
        assertThat(SbaCli.ingestText(new DefaultApplicationArguments("ingest"), unread, true))
                .isNull();
    }

    @Test
    void emptyEofIsAnEmptyCapture() throws Exception {
        assertThat(read(new byte[0])).isEmpty();
    }

    @Test
    void byteLimitIncludesCompleteMultibyteTextWithoutClipping() throws Exception {
        byte[] bytes = new byte[SbaCli.MAX_STDIN_BYTES];
        Arrays.fill(bytes, (byte) 'x');
        byte[] suffix = "🐈".getBytes(StandardCharsets.UTF_8);
        System.arraycopy(suffix, 0, bytes, bytes.length - suffix.length, suffix.length);
        assertThat(read(bytes)).isEqualTo("x".repeat(bytes.length - 4) + "🐈");
    }

    @Test
    void oversizeReadsOnlyOneOverflowByteThenErrors() {
        byte[] bytes = new byte[SbaCli.MAX_STDIN_BYTES + 100];
        ByteArrayInputStream input = new ByteArrayInputStream(bytes);
        assertThatThrownBy(() -> SbaCli.ingestText(new DefaultApplicationArguments("ingest"), input, false))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("1048576-byte limit");
        assertThat(input.available()).isEqualTo(99);
    }

    @Test
    void incompleteAndMalformedUtf8AreErrors() {
        for (byte[] bytes :
                new byte[][] {{(byte) 0xc3}, {(byte) 0xc3, 0x28}, {(byte) 0xed, (byte) 0xa0, (byte) 0x80}}) {
            assertThatThrownBy(() -> read(bytes)).isInstanceOf(CharacterCodingException.class);
        }
    }

    @Test
    void readFailureIsNotConvertedToAnEmptyCapture() {
        InputStream broken = new InputStream() {
            @Override
            public int read() throws IOException {
                throw new IOException("synthetic stdin failure");
            }
        };
        assertThatThrownBy(() -> SbaCli.ingestText(new DefaultApplicationArguments("ingest"), broken, false))
                .isInstanceOf(IOException.class)
                .hasMessage("synthetic stdin failure");
    }

    private String read(byte[] bytes) throws IOException {

        return SbaCli.ingestText(new DefaultApplicationArguments("ingest"), new ByteArrayInputStream(bytes), false);
    }
}
