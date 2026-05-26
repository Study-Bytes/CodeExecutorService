package com.example.demo.core.docker;

import com.example.demo.api.dto.OutputBlob;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

class StreamUtilTest {

    @Test
    void readStreamLimitedReturnsFullDataWhenUnderLimit() throws Exception {
        ByteArrayInputStream input = input("hello");

        OutputBlob output = StreamUtil.readStreamLimited(input, 10);

        assertThat(output.getData()).isEqualTo("hello");
        assertThat(output.isTruncated()).isFalse();
    }

    @Test
    void readStreamLimitedTruncatesAtByteLimitAndDrainsStream() throws Exception {
        ByteArrayInputStream input = input("abcdef");

        OutputBlob output = StreamUtil.readStreamLimited(input, 3);

        assertThat(output.getData()).isEqualTo("abc");
        assertThat(output.isTruncated()).isTrue();
        assertThat(input.read()).isEqualTo(-1);
    }

    private ByteArrayInputStream input(String value) {
        return new ByteArrayInputStream(value.getBytes(StandardCharsets.UTF_8));
    }
}
