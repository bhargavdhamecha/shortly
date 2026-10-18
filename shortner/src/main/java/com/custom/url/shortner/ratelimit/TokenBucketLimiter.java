package com.custom.url.shortner.ratelimit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.scripting.support.ResourceScriptSource;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Distributed token bucket. Read-refill-decide-write has to be atomic or concurrent
 * requests race straight through the limit, so the whole decision runs as one Lua script.
 */
@Component
public class TokenBucketLimiter {

    private static final Logger log = LoggerFactory.getLogger(TokenBucketLimiter.class);

    /** Crude bound on fallback memory: only reached while Redis is down. */
    private static final int MAX_LOCAL_BUCKETS = 50_000;

    /**
     * After a failure, stop calling Redis for this long. Without it every request pays the
     * full Redis timeout while Redis is down, which is how a cache outage becomes a
     * latency outage. One request per cooldown probes for recovery.
     */
    private static final long COOLDOWN_NANOS = Duration.ofSeconds(5).toNanos();

    private final StringRedisTemplate redis;

    private final RedisScript<List> script;

    private final int instances;

    private final Map<String, LocalBucket> localBuckets = new ConcurrentHashMap<>();

    private final AtomicBoolean degraded = new AtomicBoolean();

    private final AtomicLong skipRedisUntil = new AtomicLong();

    public TokenBucketLimiter(StringRedisTemplate redis, RateLimitProperties properties) {
        this.redis = redis;
        this.instances = Math.max(1, properties.instances());
        DefaultRedisScript<List> redisScript = new DefaultRedisScript<>();
        redisScript.setScriptSource(new ResourceScriptSource(new ClassPathResource("rate_limit.lua")));
        redisScript.setResultType(List.class);
        this.script = redisScript;
    }

    /**
     * @param cost 1 to consume a token, 0 to report whether one is available without charging
     */
    @SuppressWarnings("unchecked")
    public Decision check(List<BucketSpec> buckets, int cost) {
        // Circuit open: skip Redis entirely rather than wait out its timeout again.
        long skipUntil = skipRedisUntil.get();
        if (skipUntil != 0 && System.nanoTime() - skipUntil < 0) {
            return localDecision(buckets, cost);
        }

        List<String> keys = new ArrayList<>(buckets.size());
        List<String> args = new ArrayList<>(1 + buckets.size() * 2);
        args.add(Integer.toString(cost));
        for (BucketSpec bucket : buckets) {
            keys.add(bucket.key());
            args.add(Long.toString(bucket.capacity()));
            args.add(Double.toString(bucket.refillPerSecond()));
        }

        try {
            List<Long> result = (List<Long>) redis.execute(script, keys, args.toArray());
            if (result == null || result.size() < 3) {
                throw new IllegalStateException("Unexpected rate limit script result: " + result);
            }
            if (degraded.compareAndSet(true, false)) {
                skipRedisUntil.set(0);
                log.info("Rate limiter is using Redis again");
            }
            return new Decision(result.get(0) == 1L, result.get(1), result.get(2), false);
        }
        catch (RuntimeException e) {
            // Fail open to a local bucket. Failing closed would turn a Redis blip into an
            // outage; allowing everything would hand an attacker unlimited access.
            // Logged only on transition, because a storm of these is what fills disks.
            skipRedisUntil.set(System.nanoTime() + COOLDOWN_NANOS);
            if (degraded.compareAndSet(false, true)) {
                log.warn("Rate limiter degraded to per-instance local buckets: {}", e.toString());
            }
            return localDecision(buckets, cost);
        }
    }

    public boolean isDegraded() {
        return degraded.get();
    }

    private Decision localDecision(List<BucketSpec> buckets, int cost) {
        if (localBuckets.size() > MAX_LOCAL_BUCKETS) {
            localBuckets.clear();
        }

        List<LocalBucket> resolved = new ArrayList<>(buckets.size());
        for (BucketSpec bucket : buckets) {
            long capacity = Math.max(1, bucket.capacity() / instances);
            double rate = bucket.refillPerSecond() / instances;
            resolved.add(localBuckets.computeIfAbsent(bucket.key(), key -> new LocalBucket(capacity, rate)));
        }

        boolean allowed = true;
        double remaining = Double.MAX_VALUE;
        double retryAfter = 0;
        for (LocalBucket bucket : resolved) {
            double tokens = bucket.refill();
            remaining = Math.min(remaining, tokens);
            if (tokens < 1) {
                allowed = false;
                retryAfter = Math.max(retryAfter, Math.ceil((1 - tokens) / bucket.refillPerSecond));
            }
        }
        if (allowed && cost > 0) {
            resolved.forEach(bucket -> bucket.deduct(cost));
            remaining -= cost;
        }
        return new Decision(allowed, (long) Math.floor(Math.max(0, remaining)), (long) retryAfter, true);
    }

    public record BucketSpec(String key, long capacity, double refillPerSecond) {
    }

    public record Decision(boolean allowed, long remaining, long retryAfterSeconds, boolean degraded) {
    }

    /**
     * Per-instance fallback. Buckets are not charged atomically across a layered limit,
     * which is acceptable only because this runs while Redis is already unavailable.
     */
    private static final class LocalBucket {

        private final double capacity;

        private final double refillPerSecond;

        private double tokens;

        private long lastNanos;

        LocalBucket(double capacity, double refillPerSecond) {
            this.capacity = capacity;
            this.refillPerSecond = refillPerSecond;
            this.tokens = capacity;
            this.lastNanos = System.nanoTime();
        }

        synchronized double refill() {
            long now = System.nanoTime();
            tokens = Math.min(capacity, tokens + ((now - lastNanos) / 1_000_000_000.0d) * refillPerSecond);
            lastNanos = now;
            return tokens;
        }

        synchronized void deduct(int cost) {
            tokens = Math.max(0, tokens - cost);
        }
    }
}
