package com.custom.url.shortner.ratelimit;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.util.Set;

/**
 * Fine-grained limits enforced by the service itself. The gateway applies coarse
 * per-IP limits, but the service is still reachable directly, so this is the backstop.
 */
@ConfigurationProperties(prefix = "app.rate-limit")
public record RateLimitProperties(
        @DefaultValue("true") boolean enabled,
        /*
         * Used only while Redis is unreachable: the in-memory fallback divides each limit
         * by the number of instances so the cluster-wide total stays roughly correct.
         */
        @DefaultValue("1") int instances,
        /*
         * Peers whose X-Client-Key header is believed: the gateway, and nothing else.
         * A request arriving from anywhere else is keyed by its own address, so exposing
         * this service directly cannot be used to forge someone else's bucket.
         */
        @DefaultValue({ "127.0.0.1", "0:0:0:0:0:0:0:1" }) Set<String> trustedProxies,
        @DefaultValue Limit create,
        @DefaultValue Limit miss) {

    /** A layered limit. {@code perDay} of 0 disables the daily bucket. */
    public record Limit(
            @DefaultValue("10") long perMinute,
            @DefaultValue("20") long burst,
            @DefaultValue("0") long perDay) {

        public double perSecond() {
            return perMinute / 60.0d;
        }

        public double perDayPerSecond() {
            return perDay / 86400.0d;
        }
    }
}
