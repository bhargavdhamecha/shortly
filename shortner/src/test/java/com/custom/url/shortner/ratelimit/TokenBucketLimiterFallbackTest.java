package com.custom.url.shortner.ratelimit;

import com.custom.url.shortner.ratelimit.TokenBucketLimiter.BucketSpec;
import com.custom.url.shortner.ratelimit.TokenBucketLimiter.Decision;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class TokenBucketLimiterFallbackTest {

    private StringRedisTemplate redis;

    private TokenBucketLimiter limiter;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        redis = mock(StringRedisTemplate.class);
        when(redis.execute(any(RedisScript.class), anyList(), any(Object[].class)))
                .thenThrow(new RedisConnectionFailureException("Redis unavailable"));
        limiter = new TokenBucketLimiter(redis, new RateLimitProperties(true, 1, Set.of("127.0.0.1"),
                new RateLimitProperties.Limit(10, 20, 200), new RateLimitProperties.Limit(20, 30, 0)));
    }

    private static List<BucketSpec> buckets() {
        return List.of(new BucketSpec("rl:test:203.0.113.9", 20, 10 / 60.0d));
    }

    /**
     * Without the cooldown every request waits out the Redis timeout, which is how a
     * cache outage turns into a latency outage.
     */
    @Test
    @SuppressWarnings("unchecked")
    void redisIsNotRetriedOnEveryRequestWhileItIsDown() {
        for (int i = 0; i < 10; i++) {
            assertTrue(limiter.check(buckets(), 1).degraded(), "request " + i + " should use the local fallback");
        }

        verify(redis, times(1)).execute(any(RedisScript.class), anyList(), any(Object[].class));
        assertTrue(limiter.isDegraded());
    }

    @Test
    void localFallbackStillEnforcesTheLimit() {
        List<BucketSpec> buckets = buckets();

        int allowed = 0;
        for (int i = 0; i < 30; i++) {
            Decision decision = limiter.check(buckets, 1);
            if (decision.allowed()) {
                allowed++;
            }
        }

        assertTrue(allowed <= 21, "fallback allowed " + allowed + ", which is not a limit");
        assertFalse(limiter.check(buckets, 1).allowed(), "fallback should reject once drained");
    }
}
