package ffdd.opsconsole.content.application;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import ffdd.opsconsole.content.domain.SupportPerformance;
import ffdd.opsconsole.shared.exception.BizException;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;

/**
 * B80 v2 publication cache. Its names/lock are deliberately separate from the analytics v1
 * and leaderboard v1 stores, so old readers and rollback paths never observe a new generation.
 */
@Service
public class SupportPerformanceCache {
    static final String SNAPSHOT_KEY = "uvel:support:performance:v2:snapshot";
    static final String LOCK_KEY = "uvel:support:performance:v2:lock";
    static final Duration LOCK_LEASE = Duration.ofMinutes(2);
    private static final DefaultRedisScript<Long> PUBLISH = new DefaultRedisScript<>("""
        if redis.call('GET', KEYS[1]) ~= ARGV[1] then return 0 end
        redis.call('SET', KEYS[2], ARGV[2])
        redis.call('DEL', KEYS[1])
        return 1
        """, Long.class);
    private static final DefaultRedisScript<Long> RELEASE = new DefaultRedisScript<>("""
        if redis.call('GET', KEYS[1]) ~= ARGV[1] then return 0 end
        return redis.call('DEL', KEYS[1])
        """, Long.class);
    private final StringRedisTemplate redis;
    private final ObjectMapper json = new ObjectMapper().registerModule(new JavaTimeModule())
            .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES,
                    DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
    private final AtomicReference<Decoded> decoded = new AtomicReference<>();
    private record Decoded(String payload, SupportPerformance.Snapshot snapshot) { }

    public SupportPerformanceCache(StringRedisTemplate redis) { this.redis = redis; }

    public Optional<SupportPerformance.Snapshot> current() {
        final String payload;
        try { payload = redis.opsForValue().get(SNAPSHOT_KEY); }
        catch (DataAccessException failure) { throw unavailable(); }
        if (payload == null) return Optional.empty();
        Decoded previous = decoded.get();
        if (previous != null && previous.payload().equals(payload)) return Optional.of(previous.snapshot());
        try {
            SupportPerformance.Snapshot snapshot = json.readValue(payload, SupportPerformance.Snapshot.class);
            if (snapshot.asOf().isAfter(Instant.now().plusSeconds(5))) throw invalid();
            decoded.set(new Decoded(payload, snapshot));
            return Optional.of(snapshot);
        } catch (java.io.IOException | RuntimeException failure) {
            throw new BizException(503, "SUPPORT_PERFORMANCE_CACHE_INVALID");
        }
    }

    /** A v2 publication is one complete generation; its key is never shared with v1. */
    public boolean publish(SupportPerformance.Snapshot snapshot) {
        if (snapshot == null) throw new IllegalArgumentException("SUPPORT_PERFORMANCE_GENERATION_REQUIRED");
        String token = UUID.randomUUID().toString();
        try {
            if (!Boolean.TRUE.equals(redis.opsForValue().setIfAbsent(LOCK_KEY, token, LOCK_LEASE))) return false;
        } catch (DataAccessException failure) { throw unavailable(); }
        try {
            String payload = json.writeValueAsString(snapshot);
            boolean published = Long.valueOf(1).equals(redis.execute(PUBLISH,
                    List.of(LOCK_KEY, SNAPSHOT_KEY), token, payload));
            if (published) decoded.set(new Decoded(payload, snapshot));
            return published;
        } catch (java.io.IOException failure) {
            throw new IllegalArgumentException("SUPPORT_PERFORMANCE_GENERATION_INVALID", failure);
        } catch (DataAccessException failure) {
            throw unavailable();
        } finally {
            try { redis.execute(RELEASE, List.of(LOCK_KEY), token); }
            catch (DataAccessException ignored) { /* lease will expire */ }
        }
    }

    private static BizException unavailable() {
        return new BizException(503, "SUPPORT_PERFORMANCE_CACHE_UNAVAILABLE");
    }
    private static IllegalArgumentException invalid() {
        return new IllegalArgumentException("SUPPORT_PERFORMANCE_CACHE_INVALID");
    }
}
