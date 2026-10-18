package com.custom.gateway.ratelimit;

import io.lettuce.core.RedisException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.servlet.function.HandlerFilterFunction;
import org.springframework.web.servlet.function.HandlerFunction;
import org.springframework.web.servlet.function.ServerResponse;

import java.time.Duration;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Wraps the gateway's built-in Bucket4j filter, which on its own returns no
 * Retry-After and turns a Redis failure into a 500 (it fails closed).
 */
public final class RateLimitFilters {

    private static final Logger log = LoggerFactory.getLogger(RateLimitFilters.class);

    private RateLimitFilters() {
    }

    public static HandlerFilterFunction<ServerResponse, ServerResponse> resilient(
            HandlerFilterFunction<ServerResponse, ServerResponse> delegate, Duration period) {

        String retryAfter = String.valueOf(Math.max(1, period.toSeconds()));

        return (request, next) -> {
            AtomicBoolean downstreamInvoked = new AtomicBoolean();
            HandlerFunction<ServerResponse> guardedNext = downstreamRequest -> {
                downstreamInvoked.set(true);
                return next.handle(downstreamRequest);
            };
            ServerResponse response;
            try {
                response = delegate.filter(request, guardedNext);
            }
            catch (Exception e) {
                if (containsInterruptedException(e)) {
                    Thread.currentThread().interrupt();
                }
                // Exceptions from next.handle belong to the upstream request. Retrying
                // them here could execute non-idempotent routes (such as URL creation) twice.
                if (downstreamInvoked.get() || !isLimiterFailure(e)) {
                    throw e;
                }
                // Fail open: a limiter outage must not become an API outage. The service
                // behind this gateway runs its own limiter as the backstop.
                log.warn("Rate limiter unavailable, allowing request: {}", e.toString());
                return guardedNext.handle(request);
            }

            if (response.statusCode().value() != HttpStatus.TOO_MANY_REQUESTS.value()) {
                return response;
            }
            // Without Retry-After a well-behaved client has no option but to keep retrying.
            return ServerResponse.status(HttpStatus.TOO_MANY_REQUESTS)
                    .headers(headers -> headers.addAll(response.headers()))
                    .header(HttpHeaders.RETRY_AFTER, retryAfter)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body("{\"error\":\"rate limit exceeded\"}");
        };
    }

    private static boolean isLimiterFailure(Throwable throwable) {
        for (Throwable cause = throwable; cause != null; cause = cause.getCause()) {
            if (cause instanceof TimeoutException || cause instanceof ExecutionException
                    || cause instanceof CompletionException || cause instanceof InterruptedException
                    || cause instanceof RedisException) {
                return true;
            }
            if (cause == cause.getCause()) {
                break;
            }
        }
        return false;
    }

    private static boolean containsInterruptedException(Throwable throwable) {
        for (Throwable cause = throwable; cause != null; cause = cause.getCause()) {
            if (cause instanceof InterruptedException) {
                return true;
            }
            if (cause == cause.getCause()) {
                break;
            }
        }
        return false;
    }
}
