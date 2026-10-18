package com.custom.gateway.ratelimit;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.time.Duration;

/**
 * Coarse, per-IP limits enforced at the edge. Fine-grained, per-endpoint limits
 * live in the shortner service, which is also reachable directly.
 */
@ConfigurationProperties(prefix = "app.rate-limit")
public record RateLimitProperties(
        @DefaultValue("http://localhost:8081") String upstream,
        @DefaultValue("redis://localhost:6380") String redisUri,
        @DefaultValue("200ms") Duration redisTimeout,
        /*
         * The gateway is the edge, so X-Forwarded-For is client-controlled and trivially
         * spoofed. Enable this only when a trusted proxy in front overwrites the header.
         */
        @DefaultValue("false") boolean trustForwardedFor,
        @DefaultValue Limit create,
        @DefaultValue Limit redirect) {

    public record Limit(@DefaultValue("20") long capacity, @DefaultValue("1m") Duration period) {
    }
}
