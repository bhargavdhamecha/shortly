package com.custom.gateway.ratelimit;

import io.github.bucket4j.distributed.ExpirationAfterWriteStrategy;
import io.github.bucket4j.distributed.proxy.AsyncProxyManager;
import io.github.bucket4j.redis.lettuce.Bucket4jLettuce;
import io.lettuce.core.ClientOptions;
import io.lettuce.core.RedisClient;
import io.lettuce.core.RedisURI;
import io.lettuce.core.SocketOptions;
import io.lettuce.core.api.StatefulRedisConnection;
import io.lettuce.core.codec.ByteArrayCodec;
import io.lettuce.core.codec.RedisCodec;
import io.lettuce.core.codec.StringCodec;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Lazy;

import java.time.Duration;

@Configuration
@EnableConfigurationProperties(RateLimitProperties.class)
public class RateLimitRedisConfig {

    @Bean(destroyMethod = "shutdown")
    public RedisClient rateLimitRedisClient(RateLimitProperties properties) {
        // Bound both connect and command time, or a hung Redis blocks the gateway's threads.
        RedisURI redisUri = RedisURI.create(properties.redisUri());
        redisUri.setTimeout(properties.redisTimeout());
        RedisClient client = RedisClient.create(redisUri);
        client.setOptions(ClientOptions.builder()
                .socketOptions(SocketOptions.builder().connectTimeout(properties.redisTimeout()).build())
                .build());
        return client;
    }

    /**
     * Bucket4j stores bucket state as bytes. The key stays a String so buckets are
     * readable with redis-cli, and because the gateway's filter passes a String key:
     * a byte[]-keyed proxy manager fails at runtime, not at compile time.
     *
     * Lazy on purpose: the gateway must start and serve traffic when Redis is down,
     * with the rate limit filter failing open.
     */
    @Bean(destroyMethod = "close")
    @Lazy
    public StatefulRedisConnection<String, byte[]> rateLimitRedisConnection(RedisClient client) {
        return client.connect(RedisCodec.of(StringCodec.UTF8, ByteArrayCodec.INSTANCE));
    }

    @Bean
    @Lazy
    public AsyncProxyManager<String> rateLimitProxyManager(StatefulRedisConnection<String, byte[]> connection,
            RateLimitProperties properties) {
        return Bucket4jLettuce.casBasedBuilder(connection)
                // Idle buckets clean themselves up, so memory tracks active callers.
                .expirationAfterWrite(ExpirationAfterWriteStrategy
                        .basedOnTimeForRefillingBucketUpToMax(Duration.ofMinutes(10)))
                .requestTimeout(properties.redisTimeout())
                .build()
                .asAsync();
    }
}
