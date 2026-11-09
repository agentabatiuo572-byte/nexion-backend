package ffdd.opsconsole.home.application;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import ffdd.opsconsole.home.mapper.AppHomeOverviewMapper;
import ffdd.opsconsole.home.mapper.AppHomeOverviewMapper.TaskPriceHistoryRow;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Locale;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.env.Environment;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Only platform-wide Home facts; account and financial reads remain on the request path. */
@Component
@Slf4j
class HomeSharedSnapshotCache {
    private static final long MAX_AGE_MILLIS = 5_000;
    private static final ZoneId SERVER_ZONE = ZoneId.of("Asia/Shanghai");
    private final AppHomeOverviewMapper mapper;
    private final StringRedisTemplate redis;
    private final Clock clock;
    private final Environment environment;
    private final String key;
    // The HTTP mapper formats to whole seconds. Cache timestamps must retain their SQL precision.
    private final JsonMapper json = JsonMapper.builder().addModule(new JavaTimeModule())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
            .disable(DeserializationFeature.ACCEPT_FLOAT_AS_INT)
            .enable(DeserializationFeature.FAIL_ON_MISSING_CREATOR_PROPERTIES,
                    DeserializationFeature.FAIL_ON_TRAILING_TOKENS).build();

    HomeSharedSnapshotCache(AppHomeOverviewMapper mapper, StringRedisTemplate redis, Clock clock,
                            Environment environment) {
        this.mapper = mapper;
        this.redis = redis;
        this.clock = clock;
        this.environment = environment;
        String[] profiles = environment.getActiveProfiles();
        String profile = profiles.length == 0 ? "default"
                : profiles.length == 1 ? profiles[0].trim().toLowerCase(Locale.ROOT) : "";
        this.key = List.of("dev", "prod", "default").contains(profile)
                ? "uvel:home:shared:v1:" + profile + ":publictest:"
                    + environment.getProperty("nexion.deployment.public-test", Boolean.class, false)
                : null;
    }

    record Snapshot(long validUntil, Long activeRuleSlotsCount, List<TaskPriceHistoryRow> marketTaskPriceHistory) {}

    Snapshot current() {
        if (key == null) return null;
        try {
            String cached = redis.opsForValue().get(key);
            if (cached == null) return null;
            Snapshot snapshot = json.readValue(cached, Snapshot.class);
            long now = clock.millis();
            if (snapshot == null || snapshot.validUntil() <= now || snapshot.validUntil() > now + MAX_AGE_MILLIS
                    || snapshot.activeRuleSlotsCount() == null || snapshot.activeRuleSlotsCount() < 0
                    || snapshot.marketTaskPriceHistory() == null
                    || snapshot.marketTaskPriceHistory().stream().anyMatch(row -> row == null
                        || row.taskId() == null || row.price() == null || row.observedAt() == null)) return null;
            return snapshot;
        } catch (DataAccessException | JsonProcessingException ex) {
            log.debug("Home shared snapshot read failed; using request SQL", ex);
            return null;
        }
    }

    // Refresh on a start-to-start cadence while retaining the original five-second sampling budget.
    @Scheduled(fixedRate = 2_000)
    public void refresh() {
        if (key == null) return;
        // Start the freshness budget before either SQL statement, including their pool wait time.
        long validUntil = clock.millis() + MAX_AGE_MILLIS;
        try {
            long slots = environment.getProperty("nexion.simulated-tasks.enabled", Boolean.class, false)
                    ? mapper.activeRuleSlots("PRODUCTION", false,
                        LocalDateTime.ofInstant(clock.instant(), SERVER_ZONE)) : 0L;
            List<TaskPriceHistoryRow> history = mapper.marketTaskPriceHistory();
            String payload = json.writeValueAsString(new Snapshot(validUntil, slots, history == null ? List.of() : history));
            long remainingMillis = validUntil - clock.millis();
            if (remainingMillis > 0) redis.opsForValue().set(key, payload, Duration.ofMillis(remainingMillis));
        } catch (DataAccessException | JsonProcessingException ex) {
            log.debug("Home shared snapshot refresh failed; request SQL remains available", ex);
        }
    }
}
