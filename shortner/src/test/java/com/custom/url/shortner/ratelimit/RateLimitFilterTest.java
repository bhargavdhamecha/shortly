package com.custom.url.shortner.ratelimit;

import com.custom.url.shortner.ratelimit.TokenBucketLimiter.BucketSpec;
import com.custom.url.shortner.ratelimit.TokenBucketLimiter.Decision;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class RateLimitFilterTest {

    private TokenBucketLimiter limiter;

    private RateLimitFilter filter;

    private MockHttpServletResponse response;

    private AtomicBoolean chainCalled;

    private FilterChain chain;

    @BeforeEach
    void setUp() {
        limiter = mock(TokenBucketLimiter.class);
        RateLimitProperties properties = new RateLimitProperties(true, 1, Set.of("127.0.0.1"),
                new RateLimitProperties.Limit(10, 20, 200), new RateLimitProperties.Limit(20, 30, 0));
        filter = new RateLimitFilter(properties, limiter, new SimpleMeterRegistry());
        response = new MockHttpServletResponse();
        chainCalled = new AtomicBoolean();
        chain = (request, res) -> chainCalled.set(true);
    }

    private static MockHttpServletRequest request(String method, String uri) {
        MockHttpServletRequest request = new MockHttpServletRequest(method, uri);
        request.setRemoteAddr("203.0.113.9");
        return request;
    }

    private void allow() {
        when(limiter.check(anyList(), anyInt())).thenReturn(new Decision(true, 19, 0, false));
    }

    @Test
    void allowedCreateReachesTheApplication() throws Exception {
        allow();

        filter.doFilter(request("POST", "/generate"), response, chain);

        assertTrue(chainCalled.get());
        assertEquals(200, response.getStatus());
        assertEquals("10", response.getHeader("RateLimit-Limit"));
        assertEquals("19", response.getHeader("RateLimit-Remaining"));
    }

    @Test
    void rejectedCreateNeverReachesTheApplication() throws Exception {
        when(limiter.check(anyList(), anyInt())).thenReturn(new Decision(false, 0, 6, false));

        filter.doFilter(request("POST", "/generate"), response, chain);

        assertFalse(chainCalled.get(), "request reached the controller despite being rate limited");
        assertEquals(429, response.getStatus());
        assertEquals("6", response.getHeader("Retry-After"));
        assertEquals("application/json", response.getContentType());
    }

    @Test
    void createChargesBothTheMinuteAndDailyBuckets() throws Exception {
        allow();

        filter.doFilter(request("POST", "/generate"), response, chain);

        ArgumentCaptor<List<BucketSpec>> buckets = ArgumentCaptor.captor();
        verify(limiter).check(buckets.capture(), eq(1));
        List<String> keys = buckets.getValue().stream().map(BucketSpec::key).toList();
        assertEquals(List.of("rl:create:m:203.0.113.9", "rl:create:d:203.0.113.9"), keys);
    }

    /** A hit is free; only lookups that reached Postgres and found nothing are charged. */
    @Test
    void successfulRedirectIsNotCharged() throws Exception {
        allow();

        filter.doFilter(request("GET", "/abc123"), response, chain);

        assertTrue(chainCalled.get());
        verify(limiter).check(anyList(), eq(0));
        verify(limiter, never()).check(anyList(), eq(1));
    }

    @Test
    void missingShortCodeIsChargedAfterTheFact() throws Exception {
        allow();
        chain = (request, res) -> {
            chainCalled.set(true);
            response.setStatus(404);
        };

        filter.doFilter(request("GET", "/abc123"), response, chain);

        verify(limiter).check(anyList(), eq(0));
        verify(limiter).check(anyList(), eq(1));
    }

    @Test
    void exhaustedMissBudgetBlocksEnumerationBeforeTheDatabase() throws Exception {
        when(limiter.check(anyList(), eq(0))).thenReturn(new Decision(false, 0, 3, false));

        filter.doFilter(request("GET", "/abc123"), response, chain);

        assertFalse(chainCalled.get());
        assertEquals(429, response.getStatus());
        assertEquals("3", response.getHeader("Retry-After"));
    }

    @Test
    void actuatorAndDocsAreNotRateLimited() throws Exception {
        filter.doFilter(request("GET", "/actuator/health"), response, chain);

        assertTrue(chainCalled.get());
        verify(limiter, never()).check(anyList(), anyInt());
    }

    /** Behind the gateway, every request would otherwise share the gateway's bucket. */
    @Test
    void clientKeyStampedByTheGatewayIsHonoured() throws Exception {
        allow();
        MockHttpServletRequest request = request("POST", "/generate");
        request.setRemoteAddr("127.0.0.1");
        request.addHeader("X-Client-Key", "198.51.100.7");

        filter.doFilter(request, response, chain);

        ArgumentCaptor<List<BucketSpec>> buckets = ArgumentCaptor.captor();
        verify(limiter).check(buckets.capture(), eq(1));
        assertEquals("rl:create:m:198.51.100.7", buckets.getValue().get(0).key());
    }

    @Test
    void anUntrustedPeerCannotForgeSomeoneElsesBucket() throws Exception {
        allow();
        MockHttpServletRequest request = request("POST", "/generate");
        request.addHeader("X-Client-Key", "198.51.100.7");

        filter.doFilter(request, response, chain);

        ArgumentCaptor<List<BucketSpec>> buckets = ArgumentCaptor.captor();
        verify(limiter).check(buckets.capture(), eq(1));
        assertEquals("rl:create:m:203.0.113.9", buckets.getValue().get(0).key());
    }

    @Test
    void ipv6ClientsAreLimitedByPrefixNotAddress() {
        assertEquals("2001:0db8:85a3:0000::/64", RateLimitFilter.normalise("2001:db8:85a3:0:1:2:3:4"));
        assertEquals("203.0.113.9", RateLimitFilter.normalise("203.0.113.9"));
    }
}
