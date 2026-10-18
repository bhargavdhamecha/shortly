package com.custom.url.shortner.ratelimit;

import com.custom.url.shortner.ratelimit.TokenBucketLimiter.BucketSpec;
import com.custom.url.shortner.ratelimit.TokenBucketLimiter.Decision;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Exercises the Lua script against a real Redis, because the property that matters
 * (atomic read-refill-decide-write) cannot be observed with a mock.
 *
 * Skipped when Redis is not running: start it with {@code docker compose up -d}.
 */
class TokenBucketLimiterRedisTest {

    private static final String HOST = System.getProperty("redis.host", "localhost");

    private static final int PORT = Integer.getInteger("redis.port", 6380);

    private static LettuceConnectionFactory factory;

    private static StringRedisTemplate redis;

    private static TokenBucketLimiter limiter;

    @BeforeAll
    static void connect() {
        LettuceConnectionFactory candidate = new LettuceConnectionFactory(
                new RedisStandaloneConfiguration(HOST, PORT));
        candidate.afterPropertiesSet();
        candidate.start();
        try {
            candidate.getConnection().ping();
        }
        catch (RuntimeException e) {
            candidate.destroy();
            Assumptions.abort("Redis is not reachable at " + HOST + ":" + PORT + " (docker compose up -d)");
            return;
        }
        factory = candidate;
        redis = new StringRedisTemplate(factory);
        redis.afterPropertiesSet();
        limiter = new TokenBucketLimiter(redis, new RateLimitProperties(true, 1, java.util.Set.of("127.0.0.1"),
                new RateLimitProperties.Limit(10, 20, 200), new RateLimitProperties.Limit(20, 30, 0)));
    }

    @AfterAll
    static void disconnect() {
        if (factory != null) {
            factory.destroy();
        }
    }

    private static List<BucketSpec> bucket(long capacity, double refillPerSecond) {
        return List.of(new BucketSpec("test:rl:" + UUID.randomUUID(), capacity, refillPerSecond));
    }

    @Test
    void burstIsAllowedThenExhausted() {
        List<BucketSpec> buckets = bucket(20, 10 / 60.0d);

        for (int i = 0; i < 20; i++) {
            assertTrue(limiter.check(buckets, 1).allowed(), "request " + (i + 1) + " should fit the burst");
        }

        Decision rejected = limiter.check(buckets, 1);
        assertFalse(rejected.allowed());
        assertTrue(rejected.retryAfterSeconds() > 0, "a rejection must tell the client when to retry");
        assertFalse(rejected.degraded(), "Redis is up, so this must not be the local fallback");
    }

    /** The test a non-atomic limiter fails: concurrent callers must not overshoot. */
    @Test
    void concurrentRequestsNeverExceedTheCapacity() throws Exception {
        List<BucketSpec> buckets = bucket(20, 0.001d);
        int threads = 16;
        int perThread = 5;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger allowed = new AtomicInteger();

        try {
            for (int t = 0; t < threads; t++) {
                pool.submit(() -> {
                    start.await();
                    for (int i = 0; i < perThread; i++) {
                        if (limiter.check(buckets, 1).allowed()) {
                            allowed.incrementAndGet();
                        }
                    }
                    return null;
                });
            }
            start.countDown();
            pool.shutdown();
            assertTrue(pool.awaitTermination(30, TimeUnit.SECONDS), "workers did not finish");
        }
        finally {
            pool.shutdownNow();
        }

        assertEquals(20, allowed.get(), "80 concurrent requests must yield exactly the 20 available tokens");
    }

    @Test
    void peekReportsAvailabilityWithoutConsuming() {
        List<BucketSpec> buckets = bucket(3, 0.001d);

        assertTrue(limiter.check(buckets, 0).allowed());
        assertTrue(limiter.check(buckets, 0).allowed());
        for (int i = 0; i < 3; i++) {
            assertTrue(limiter.check(buckets, 1).allowed());
        }
        assertFalse(limiter.check(buckets, 0).allowed(), "peek must report an exhausted bucket");
    }

    /** A daily ceiling must not silently burn tokens from the per-minute bucket. */
    @Test
    void layeredLimitsAreChargedAllOrNothing() {
        String suffix = UUID.randomUUID().toString();
        List<BucketSpec> buckets = List.of(new BucketSpec("test:rl:m:" + suffix, 100, 10),
                new BucketSpec("test:rl:d:" + suffix, 3, 0.001d));

        for (int i = 0; i < 3; i++) {
            assertTrue(limiter.check(buckets, 1).allowed());
        }
        assertFalse(limiter.check(buckets, 1).allowed(), "daily ceiling should reject");

        String minuteTokens = (String) redis.opsForHash().get("test:rl:m:" + suffix, "tokens");
        assertTrue(Double.parseDouble(minuteTokens) > 96.5,
                "minute bucket was charged for a request the daily bucket rejected: " + minuteTokens);
    }
}
