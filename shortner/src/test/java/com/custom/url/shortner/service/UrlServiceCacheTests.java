//package com.custom.url.shortner.service;
//
//import com.custom.url.shortner.dto.CreateUrlRequest;
//import com.custom.url.shortner.model.UrlEntity;
//import com.custom.url.shortner.repository.UrlEntityRepository;
//import com.custom.url.shortner.utils.Base62Utils;
//import org.junit.jupiter.api.BeforeEach;
//import org.junit.jupiter.api.Test;
//import org.mockito.ArgumentCaptor;
//import org.springframework.data.redis.RedisConnectionFailureException;
//import org.springframework.data.redis.core.StringRedisTemplate;
//import org.springframework.data.redis.core.ValueOperations;
//import org.springframework.http.HttpStatus;
//import org.springframework.test.util.ReflectionTestUtils;
//import org.springframework.web.server.ResponseStatusException;
//
//import java.time.Duration;
//import java.time.Instant;
//import java.time.temporal.ChronoUnit;
//import java.util.Optional;
//import java.util.UUID;
//
//import static org.junit.jupiter.api.Assertions.assertEquals;
//import static org.junit.jupiter.api.Assertions.assertThrows;
//import static org.junit.jupiter.api.Assertions.assertTrue;
//import static org.mockito.ArgumentMatchers.any;
//import static org.mockito.ArgumentMatchers.eq;
//import static org.mockito.Mockito.doThrow;
//import static org.mockito.Mockito.mock;
//import static org.mockito.Mockito.never;
//import static org.mockito.Mockito.verify;
//import static org.mockito.Mockito.verifyNoInteractions;
//import static org.mockito.Mockito.when;
//
//class UrlServiceCacheTests {
//
//    private static final UUID ID = UUID.fromString("6da3e3a1-74a0-497c-b547-3719c6489058");
//
//    private static final String CODE = Base62Utils.encode(ID);
//
//    private static final String KEY = "shortner:v1:" + CODE;
//
//    private static final String URL = "https://example.com/";
//
//    private UrlEntityRepository repository;
//
//    private StringRedisTemplate redis;
//
//    private ValueOperations<String, String> values;
//
//    private UrlService service;
//
//    @BeforeEach
//    @SuppressWarnings("unchecked")
//    void setUp() {
//        repository = mock(UrlEntityRepository.class);
//        redis = mock(StringRedisTemplate.class);
//        values = mock(ValueOperations.class);
//        when(redis.opsForValue()).thenReturn(values);
//        service = new UrlService(repository, redis);
//        ReflectionTestUtils.setField(service, "baseUrl", "http://localhost:8081");
//    }
//
//    private static UrlEntity entity(Instant expiresAt) {
//        UrlEntity entity = new UrlEntity();
//        entity.setLongUrl(URL);
//        entity.setExpiresAt(expiresAt);
//        return entity;
//    }
//
//    @Test
//    void cacheHitSkipsDatabase() {
//        when(values.get(KEY)).thenReturn(URL);
//
//        assertEquals(URL, service.findOriginalUrl(CODE));
//
//        verifyNoInteractions(repository);
//        verify(values, never()).set(any(), any(), any(Duration.class));
//    }
//
//    @Test
//    void cacheMissLoadsDatabaseAndCachesForThirtyMinutes() {
//        when(repository.findActiveLongUrl(eq(ID), any(Instant.class)))
//                .thenReturn(Optional.of(entity(Instant.now().plus(3, ChronoUnit.HOURS))));
//
//        assertEquals(URL, service.findOriginalUrl(CODE));
//
//        ArgumentCaptor<Duration> ttl = ArgumentCaptor.forClass(Duration.class);
//        verify(values).set(eq(KEY), eq(URL), ttl.capture());
//        assertEquals(Duration.ofMinutes(30), ttl.getValue());
//    }
//
//    /** The entry must never outlive the link, or an expired URL keeps redirecting. */
//    @Test
//    void ttlIsCappedByTheExpiry() {
//        when(repository.findActiveLongUrl(eq(ID), any(Instant.class)))
//                .thenReturn(Optional.of(entity(Instant.now().plus(5, ChronoUnit.MINUTES))));
//
//        service.findOriginalUrl(CODE);
//
//        ArgumentCaptor<Duration> ttl = ArgumentCaptor.forClass(Duration.class);
//        verify(values).set(eq(KEY), eq(URL), ttl.capture());
//        Duration captured = ttl.getValue();
//        assertTrue(captured.compareTo(Duration.ofMinutes(5)) <= 0, "ttl outlives the link: " + captured);
//        assertTrue(captured.compareTo(Duration.ofMinutes(4)) > 0, "ttl unexpectedly short: " + captured);
//    }
//
//    @Test
//    void nullExpiryCachesForThirtyMinutes() {
//        when(repository.findActiveLongUrl(eq(ID), any(Instant.class))).thenReturn(Optional.of(entity(null)));
//
//        assertEquals(URL, service.findOriginalUrl(CODE));
//
//        verify(values).set(eq(KEY), eq(URL), eq(Duration.ofMinutes(30)));
//    }
//
//    @Test
//    void unavailableRedisStillReturnsDatabaseResult() {
//        when(values.get(KEY)).thenThrow(new RedisConnectionFailureException("Redis unavailable"));
//        when(repository.findActiveLongUrl(eq(ID), any(Instant.class)))
//                .thenReturn(Optional.of(entity(null)));
//        doThrow(new RedisConnectionFailureException("Redis unavailable"))
//                .when(values).set(eq(KEY), eq(URL), any(Duration.class));
//
//        assertEquals(URL, service.findOriginalUrl(CODE));
//    }
//
//    @Test
//    void expiredOrUnknownUrlReturns404WithoutCaching() {
//        when(repository.findActiveLongUrl(eq(ID), any(Instant.class))).thenReturn(Optional.empty());
//
//        ResponseStatusException error = assertThrows(ResponseStatusException.class,
//                () -> service.findOriginalUrl(CODE));
//
//        assertEquals(HttpStatus.NOT_FOUND, error.getStatusCode());
//        verify(values, never()).set(any(), any(), any(Duration.class));
//    }
//
//    @Test
//    void invalidShortCodeReturns404WithoutQueryingDatabase() {
//        ResponseStatusException error = assertThrows(ResponseStatusException.class,
//                () -> service.findOriginalUrl("invalid!"));
//
//        assertEquals(HttpStatus.NOT_FOUND, error.getStatusCode());
//        verifyNoInteractions(repository);
//    }
//
//    @Test
//    void shortenUrlReturnsCreatedWithAbsoluteShortUrl() {
//        UrlEntity saved = entity(null);
//        ReflectionTestUtils.setField(saved, "distributedId", ID);
//        when(repository.save(any(UrlEntity.class))).thenReturn(saved);
//
//        var response = service.shortenUrl(new CreateUrlRequest(URL));
//
//        assertEquals(HttpStatus.CREATED, response.getStatusCode());
//        assertEquals("http://localhost:8081/" + CODE, response.getBody());
//    }
//}
