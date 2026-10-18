package com.custom.url.shortner.ratelimit;

import com.custom.url.shortner.ratelimit.TokenBucketLimiter.BucketSpec;
import com.custom.url.shortner.ratelimit.TokenBucketLimiter.Decision;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Rejects before the request reaches a controller, Hibernate or the connection pool.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 10)
public class RateLimitFilter extends OncePerRequestFilter {

    private static final String CREATE_PATH = "/generate";

    /** Set by the gateway, which resolves the client address at the edge. */
    public static final String CLIENT_KEY_HEADER = "X-Client-Key";

    private static final Set<String> RESERVED_PREFIXES = Set.of("/actuator", "/swagger-ui", "/v3/api-docs", "/error",
            "/favicon.ico", "/webjars");

    private final RateLimitProperties properties;

    private final TokenBucketLimiter limiter;

    private final MeterRegistry meterRegistry;

    public RateLimitFilter(RateLimitProperties properties, TokenBucketLimiter limiter, MeterRegistry meterRegistry) {
        this.properties = properties;
        this.limiter = limiter;
        this.meterRegistry = meterRegistry;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) throws ServletException {
        if (!properties.enabled()) {
            return true;
        }
        String path = request.getRequestURI();
        for (String reserved : RESERVED_PREFIXES) {
            if (path.startsWith(reserved)) {
                return true;
            }
        }
        return !isCreate(request, path) && !isRedirect(request, path);
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String client = clientKey(request);
        if (isCreate(request, request.getRequestURI())) {
            filterCreate(request, response, chain, client);
        }
        else {
            filterRedirect(request, response, chain, client);
        }
    }

    /** Writes are expensive and abuse-attractive: a per-minute burst plus a daily ceiling. */
    private void filterCreate(HttpServletRequest request, HttpServletResponse response, FilterChain chain,
            String client) throws ServletException, IOException {
        RateLimitProperties.Limit limit = properties.create();
        List<BucketSpec> buckets = new ArrayList<>(2);
        buckets.add(new BucketSpec("rl:create:m:" + client, limit.burst(), limit.perSecond()));
        if (limit.perDay() > 0) {
            // An attacker will happily sit just under a per-minute limit forever.
            buckets.add(new BucketSpec("rl:create:d:" + client, limit.perDay(), limit.perDayPerSecond()));
        }

        Decision decision = limiter.check(buckets, 1);
        response.setHeader("RateLimit-Limit", Long.toString(limit.perMinute()));
        response.setHeader("RateLimit-Remaining", Long.toString(Math.max(0, decision.remaining())));
        if (!decision.allowed()) {
            reject(response, decision, "create");
            return;
        }
        chain.doFilter(request, response);
    }

    /**
     * Redirects are the hot path and share IPs behind NAT, so limiting every request per IP
     * would block real users and double the Redis load the cache exists to avoid. Only the
     * expensive subset is charged: a miss that reached Postgres and found nothing, which is
     * what short-code enumeration looks like.
     */
    private void filterRedirect(HttpServletRequest request, HttpServletResponse response, FilterChain chain,
            String client) throws ServletException, IOException {
        RateLimitProperties.Limit limit = properties.miss();
        List<BucketSpec> buckets = List.of(new BucketSpec("rl:miss:" + client, limit.burst(), limit.perSecond()));

        Decision peek = limiter.check(buckets, 0);
        if (!peek.allowed()) {
            reject(response, peek, "miss");
            return;
        }

        chain.doFilter(request, response);

        if (response.getStatus() == HttpStatus.NOT_FOUND.value()) {
            limiter.check(buckets, 1);
        }
    }

    private void reject(HttpServletResponse response, Decision decision, String scope) throws IOException {
        meterRegistry.counter("ratelimit.rejected", "scope", scope, "degraded", Boolean.toString(decision.degraded()))
                .increment();
        response.setStatus(HttpStatus.TOO_MANY_REQUESTS.value());
        response.setHeader(HttpHeaders.RETRY_AFTER, Long.toString(Math.max(1, decision.retryAfterSeconds())));
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.getWriter().write("{\"error\":\"rate limit exceeded\"}");
    }

    private static boolean isCreate(HttpServletRequest request, String path) {
        return "POST".equals(request.getMethod()) && CREATE_PATH.equals(path);
    }

    private static boolean isRedirect(HttpServletRequest request, String path) {
        return "GET".equals(request.getMethod()) && path.length() > 1 && path.indexOf('/', 1) < 0;
    }

    /**
     * Spring Cloud Gateway only forwards X-Forwarded-For when the caller is itself a
     * trusted proxy, so behind the gateway every request would otherwise key on the
     * gateway's own address and put all users in one bucket. The gateway stamps
     * X-Client-Key instead, and it is believed only when the peer is a trusted proxy.
     */
    private String clientKey(HttpServletRequest request) {
        String peer = request.getRemoteAddr();
        if (peer != null && properties.trustedProxies().contains(peer)) {
            String stamped = request.getHeader(CLIENT_KEY_HEADER);
            if (stamped != null && !stamped.isBlank()) {
                return normalise(stamped.trim());
            }
        }
        return (peer == null || peer.isBlank()) ? "unknown" : normalise(peer);
    }

    /** A single IPv6 customer gets a whole /64, so limit the prefix, not the address. */
    static String normalise(String ip) {
        if (ip.indexOf(':') < 0) {
            return ip;
        }
        try {
            byte[] bytes = InetAddress.getByName(ip).getAddress();
            if (bytes.length != 16) {
                return ip;
            }
            StringBuilder prefix = new StringBuilder(20);
            for (int i = 0; i < 8; i += 2) {
                if (i > 0) {
                    prefix.append(':');
                }
                prefix.append(String.format("%02x%02x", bytes[i], bytes[i + 1]));
            }
            return prefix.append("::/64").toString();
        }
        catch (UnknownHostException e) {
            return ip;
        }
    }
}
