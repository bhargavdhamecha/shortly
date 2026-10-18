package com.custom.gateway.route;

import com.custom.gateway.ratelimit.ClientKeyResolver;
import com.custom.gateway.ratelimit.RateLimitProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.function.HandlerFilterFunction;
import org.springframework.web.servlet.function.RouterFunction;
import org.springframework.web.servlet.function.ServerRequest;
import org.springframework.web.servlet.function.ServerResponse;

import java.time.Duration;
import java.util.function.Function;

import static com.custom.gateway.ratelimit.RateLimitFilters.resilient;
import static org.springframework.cloud.gateway.server.mvc.filter.BeforeFilterFunctions.uri;
import static org.springframework.cloud.gateway.server.mvc.filter.Bucket4jFilterFunctions.rateLimit;
import static org.springframework.cloud.gateway.server.mvc.handler.GatewayRouterFunctions.route;
import static org.springframework.cloud.gateway.server.mvc.handler.HandlerFunctions.http;

@Configuration
public class ShortnerRoutes {

    /** Read by the shortner service's own limiter. */
    public static final String CLIENT_KEY_HEADER = "X-Client-Key";

    private final RateLimitProperties properties;

    private final ClientKeyResolver clientKeys;

    public ShortnerRoutes(RateLimitProperties properties, ClientKeyResolver clientKeys) {
        this.properties = properties;
        this.clientKeys = clientKeys;
    }

    /** Writes are expensive and abuse-attractive, so this is the tight limit. */
    @Bean
    public RouterFunction<ServerResponse> shortnerCreateRoute() {
        return route("shortner-create")
                .POST("/generate", http())
                .before(uri(properties.upstream()))
                .before(this::stampClientKey)
                .filter(limit("gw:create:", properties.create()))
                .build();
    }

    /**
     * Redirects are the hot path and share IPs behind NAT, so this limit is deliberately
     * generous: it exists to stop a single host hammering the service, not to be fair.
     * Penalising the expensive subset (cache misses and 404s) happens in the service.
     */
    @Bean
    public RouterFunction<ServerResponse> shortnerRedirectRoute() {
        return route("shortner-redirect")
                .GET("/{shortCode}", http())
                .before(uri(properties.upstream()))
                .before(this::stampClientKey)
                .filter(limit("gw:redirect:", properties.redirect()))
                .build();
    }

    /**
     * The gateway resolves the client address; the service cannot see past this hop.
     * Always set, never appended, so a client-supplied header is overwritten rather
     * than trusted.
     */
    private ServerRequest stampClientKey(ServerRequest request) {
        String key = clientKeys.resolve(request);
        return ServerRequest.from(request).headers(headers -> headers.set(CLIENT_KEY_HEADER, key)).build();
    }

    private HandlerFilterFunction<ServerResponse, ServerResponse> limit(String prefix,
            RateLimitProperties.Limit limit) {
        Function<ServerRequest, String> keyResolver = request -> prefix + clientKeys.resolve(request);
        Duration period = limit.period();
        return resilient(rateLimit(config -> config.setCapacity(limit.capacity())
                .setPeriod(period)
                .setTimeout(properties.redisTimeout())
                .setKeyResolver(keyResolver)), period);
    }
}
