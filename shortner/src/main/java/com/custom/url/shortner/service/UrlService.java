package com.custom.url.shortner.service;

import com.custom.url.shortner.dto.CreateUrlRequest;
import com.custom.url.shortner.model.UrlEntity;
import com.custom.url.shortner.repository.UrlEntityRepository;
import com.custom.url.shortner.utils.Base62Utils;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;

@Service
@RequiredArgsConstructor
public class UrlService {
    private final UrlEntityRepository urlEntityRepository;
    private static final Duration MAX_TTL = Duration.ofMinutes(30);
    private final StringRedisTemplate redis;
    private final SnowflakeIdGenerator snowflakeIdGenerator;

    @Value("${app.base-url}")
    private String baseUrl;

    public ResponseEntity<String> shortenUrl(CreateUrlRequest requestDTO) {
        Long id = snowflakeIdGenerator.generateId();
        UrlEntity entity = new UrlEntity(id);
        entity.setLongUrl(requestDTO.longUrl());
        entity.setExpiresAt(Instant.now().plus(3, ChronoUnit.HOURS));
        urlEntityRepository.save(entity);
        return new ResponseEntity<>(baseUrl + "/" + Base62Utils.encode(id), HttpStatus.CREATED);
    }

    public String findOriginalUrl(String shortCode) {
        Long id;
        try {
            id = Base62Utils.decode(shortCode);
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Short URL not found");
        }
        String key = "shortner:v1:" + shortCode;
        String lockKey = "lock:shortner:" + shortCode;

        String cached = safeGet(key);
        if (cached != null) return cached;              // hit

        Boolean acquiredLock = false;
        try {
            acquiredLock = redis.opsForValue().setIfAbsent(lockKey, "locked", Duration.ofSeconds(5));
        } catch (RuntimeException e) {
            // If Redis fails entirely, proceed to DB directly (fallback mode)
            acquiredLock = true;
        }
        if (Boolean.TRUE.equals(acquiredLock)) {
            try {
                // Double-check cache inside the lock (just in case another thread just filled it)
                cached = safeGet(key);
                if (cached != null) return cached;

                // Fetch from DB
                Instant now = Instant.now();
                UrlEntity url = urlEntityRepository.findActiveLongUrl(id, now)
                        .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Short URL not found"));

                Duration ttl = MAX_TTL;
                if (url.getExpiresAt() != null) {
                    Duration untilExpiry = Duration.between(now, url.getExpiresAt());
                    if (untilExpiry.compareTo(ttl) < 0) {
                        ttl = untilExpiry;
                    }
                }

                safeSet(key, url.getLongUrl(), ttl);
                return url.getLongUrl();

            } finally {
                // Always release the lock
                try { redis.delete(lockKey); } catch (RuntimeException ignored) {}
            }
        } else {
            // 3. Another instance/thread is fetching it. Wait briefly and retry cache once.
            try { Thread.sleep(200); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            cached = safeGet(key);
            if (cached != null) return cached;

            // Final fallback: if cache is still empty, query DB directly to avoid hanging the user
            Instant now = Instant.now();
            return urlEntityRepository.findActiveLongUrl(id, now)
                    .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Short URL not found"))
                    .getLongUrl();
        }
    }
    private String safeGet(String key) {
        try { return redis.opsForValue().get(key); }
        catch (RuntimeException e) { return null; }      // treat as a miss
    }

    private void safeSet(String key, String value, Duration ttl) {
        try { redis.opsForValue().set(key, value, ttl); }
        catch (RuntimeException e) { /* redirect still works */ }
    }
}
