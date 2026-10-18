package com.custom.url.shortner.exception;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

class IdGenerationExceptionTests {

    @Test
    void retainsCauseAndMessage() {
        IllegalStateException cause = new IllegalStateException("interrupted");

        IdGenerationException exception = new IdGenerationException("ID generation failed", cause);

        assertEquals("ID generation failed", exception.getMessage());
        assertSame(cause, exception.getCause());
    }
}
