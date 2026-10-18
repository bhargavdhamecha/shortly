package com.custom.gateway.ratelimit;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.servlet.function.HandlerFilterFunction;
import org.springframework.web.servlet.function.ServerRequest;
import org.springframework.web.servlet.function.ServerResponse;

import java.time.Duration;
import java.util.concurrent.CompletionException;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class RateLimitFiltersTests {
    private final ServerRequest request = mock(ServerRequest.class);

    @Test
    void doesNotRetryDownstreamTimeouts() {
        AtomicInteger downstreamCalls = new AtomicInteger();
        CompletionException upstreamFailure = new CompletionException(new TimeoutException("upstream timed out"));
        HandlerFilterFunction<ServerResponse, ServerResponse> delegate = (req, next) -> next.handle(req);

        CompletionException thrown = assertThrows(CompletionException.class, () ->
                RateLimitFilters.resilient(delegate, Duration.ofMinutes(1)).filter(request, req -> {
                    downstreamCalls.incrementAndGet();
                    throw upstreamFailure;
                }));

        assertSame(upstreamFailure, thrown);
        assertEquals(1, downstreamCalls.get());
    }

    @Test
    void failsOpenWhenLimiterFailsBeforeCallingDownstream() throws Exception {
        AtomicInteger downstreamCalls = new AtomicInteger();
        ServerResponse response = mock(ServerResponse.class);
        when(response.statusCode()).thenReturn(HttpStatus.OK);
        HandlerFilterFunction<ServerResponse, ServerResponse> delegate = (req, next) -> {
            throw new CompletionException(new TimeoutException("redis timed out"));
        };

        ServerResponse result = RateLimitFilters.resilient(delegate, Duration.ofMinutes(1))
                .filter(request, req -> {
                    downstreamCalls.incrementAndGet();
                    return response;
                });

        assertSame(response, result);
        assertEquals(1, downstreamCalls.get());
    }

    @Test
    void restoresInterruptFlagWhenLimiterIsInterrupted() throws Exception {
        Thread.interrupted();
        HandlerFilterFunction<ServerResponse, ServerResponse> delegate = (req, next) -> {
            throw new InterruptedException("limiter wait interrupted");
        };
        ServerResponse response = mock(ServerResponse.class);
        when(response.statusCode()).thenReturn(HttpStatus.OK);

        try {
            ServerResponse result = RateLimitFilters.resilient(delegate, Duration.ofMinutes(1))
                    .filter(request, req -> response);

            assertSame(response, result);
            assertTrue(Thread.currentThread().isInterrupted());
        } finally {
            Thread.interrupted();
        }
    }
}
