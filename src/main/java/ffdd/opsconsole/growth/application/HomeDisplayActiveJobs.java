package ffdd.opsconsole.growth.application;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import ffdd.opsconsole.growth.dto.GlobeDisplayConfig;
import ffdd.opsconsole.home.application.HomeSharedSnapshotCache;
import ffdd.opsconsole.platform.facade.PlatformConfigFacade;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Profile;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@Service
@Slf4j
@Profile({"dev", "prod", "default"})
public class HomeDisplayActiveJobs {
    private static final String FROZEN_KEY = "growth.home_display.frozen_jobs";
    private static final String CACHE_PREFIX = "uvel:home-display:active-jobs:v1:";

    private final HomeSharedSnapshotCache homeSnapshots;
    private final PlatformConfigFacade config;
    private final StringRedisTemplate redis;
    private final ObjectMapper json;
    private final TransactionTemplate transaction;
    private final Object sampleLock = new Object();

    public HomeDisplayActiveJobs(HomeSharedSnapshotCache homeSnapshots, PlatformConfigFacade config,
            StringRedisTemplate redis, ObjectMapper json, PlatformTransactionManager transactions) {
        this.homeSnapshots = homeSnapshots;
        this.config = config;
        this.redis = redis;
        this.json = json;
        this.transaction = new TransactionTemplate(transactions);
    }

    public record Sample(long sampledAt, Long total, List<Map<String, Object>> centers) {}

    record FrozenSample(long batchStartAt, Sample sample) {}

    public Sample current(boolean enabled, long batchStartAt, long now) {
        long second = Math.floorDiv(now, 1000) * 1000;
        String key = CACHE_PREFIX + (enabled ? "second:" + second : "frozen:" + batchStartAt);
        try {
            String cached = redis.opsForValue().get(key);
            if (cached != null) return json.readValue(cached, Sample.class);
            synchronized (sampleLock) {
                cached = redis.opsForValue().get(key);
                if (cached != null) return json.readValue(cached, Sample.class);
                Sample sample = enabled ? capture(second) : frozen(batchStartAt, second);
                if (Boolean.TRUE.equals(redis.opsForValue().setIfAbsent(
                        key, json.writeValueAsString(sample), Duration.ofSeconds(enabled ? 5 : 60)))) {
                    return sample;
                }
                return json.readValue(redis.opsForValue().get(key), Sample.class);
            }
        } catch (DataAccessException | JsonProcessingException | IllegalArgumentException ex) {
            log.debug("H9 active jobs sample unavailable", ex);
            return null;
        }
    }

    private Sample frozen(long batchStartAt, long second) {
        return transaction.execute(status -> {
            if (config.activeValueForUpdate(GrowthPublicStatsService.VERSION_KEY).isEmpty()) {
                return new Sample(second, null, null);
            }
            try {
                var stored = config.activeValue(FROZEN_KEY);
                if (stored.isPresent()) {
                    FrozenSample snapshot = json.readValue(stored.get(), FrozenSample.class);
                    if (snapshot.batchStartAt() == batchStartAt) return snapshot.sample();
                    if (snapshot.batchStartAt() > batchStartAt) return new Sample(second, null, null);
                }
                Sample sample = capture(second);
                config.upsertAdminValue(FROZEN_KEY, json.writeValueAsString(new FrozenSample(batchStartAt, sample)),
                        "JSON", "growth", "H9 frozen active jobs display");
                return sample;
            } catch (JsonProcessingException ex) {
                log.debug("H9 frozen active jobs sample unavailable", ex);
                return new Sample(second, null, null);
            }
        });
    }

    private Sample capture(long second) {
        try {
            var configured = config.activeValue(GrowthPublicStatsService.VALUES_KEY);
            if (configured.isEmpty()) return new Sample(second, null, null);
            Map<String, Object> values = json.readValue(configured.get(), new TypeReference<>() {});
            Long total = GrowthPublicStatsService.activeJobsDisplay(homeSnapshots.productionActiveJobsFresh(), values);
            Object globe = values.get("globeDisplay");
            var weights = globe == null ? GlobeDisplayConfig.defaults() : json.convertValue(globe, GlobeDisplayConfig.class);
            var centers = total == null || !weights.valid() ? null
                    : GrowthPublicStatsService.centerCounts(weights, total, "activeJobs");
            return new Sample(second, total, centers);
        } catch (DataAccessException | JsonProcessingException | IllegalArgumentException ex) {
            log.debug("H9 active jobs source unavailable", ex);
            return new Sample(second, null, null);
        }
    }
}
