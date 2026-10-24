package ffdd.opsconsole.growth.application;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import ffdd.opsconsole.platform.facade.PlatformConfigFacade;
import java.math.BigDecimal;
import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

@Service
@org.springframework.context.annotation.Profile({"dev", "prod", "default"})
public class HomeDisplayService {
    private static final org.slf4j.Logger LOG = org.slf4j.LoggerFactory.getLogger(HomeDisplayService.class);
    static final String STATE_KEY = "growth.home_display.batch";
    static final String CACHE_KEY = "uvel:home-display:batch:v2";
    private static final List<String> FIELDS = List.of("homeOnlineCurrent", "homeOnlineStepMin", "homeOnlineStepMax",
            "homePaidCurrent", "homePaidStepMin", "homePaidStepMax", "homeDisplayEnabled");
    private static final DefaultRedisScript<Long> CACHE_WRITE = new DefaultRedisScript<>("""
            local old = redis.call('GET', KEYS[1])
            if not old or cjson.decode(old).batch.sequence <= tonumber(ARGV[2]) then
              redis.call('SET', KEYS[1], ARGV[1], 'EX', 60)
              return 1
            end
            return 0
            """, Long.class);
    private final PlatformConfigFacade config;
    private final ObjectMapper json;
    private final StringRedisTemplate redis;
    private final Clock clock;
    private final TransactionTemplate transaction;

    public HomeDisplayService(PlatformConfigFacade config, ObjectMapper json, StringRedisTemplate redis,
                              Clock clock, PlatformTransactionManager transactions) {
        this.config = config;
        this.json = json;
        this.redis = redis;
        this.clock = clock;
        this.transaction = new TransactionTemplate(transactions);
    }

    record CachedBatch(HomeDisplayBatch batch, long validUntil) {}

    public Map<String, Object> current() {
        String cached = redis.opsForValue().get(CACHE_KEY);
        CachedBatch entry;
        try { entry = cached == null ? null : json.readValue(cached, CachedBatch.class); }
        catch (Exception ex) { throw new IllegalStateException("HOME_DISPLAY_CACHE_INVALID", ex); }
        HomeDisplayBatch batch = entry == null ? null : entry.batch();
        long now = clock.millis();
        if (batch == null || now >= entry.validUntil() || batch.enabled() && now >= batch.startAt() + (batch.online().length - 1L) * 1000) {
            batch = advance();
            entry = new CachedBatch(batch, clock.millis() + 60_000);
        }
        return Map.of("enabled", batch.enabled(), "startAt", batch.startAt(), "serverNow", clock.millis(),
                "validUntil", entry.validUntil(), "sequence", batch.sequence(), "online", batch.online(), "paidCents", batch.paidCents());
    }

    /** One platform-wide minute job, independent of connected user count. */
    @Scheduled(fixedDelay = 60_000, initialDelay = 5_000)
    public void tick() {
        if (config.activeValue(GrowthPublicStatsService.VALUES_KEY).isPresent()) advance();
    }

    private HomeDisplayBatch advance() {
        return transaction.execute(status -> {
            config.activeValueForUpdate(GrowthPublicStatsService.VERSION_KEY).orElseThrow();
            Map<String, Object> values = values();
            HomeDisplayBatch old = stored();
            HomeDisplayBatch batch = old == null ? create(values, 1, clock.millis()) : old;
            if (batch.needsExtension(clock.millis())) batch = extend(batch, values);
            if (old == null || batch != old) persist(batch);
            else publishAfterCommit(batch);
            return batch;
        });
    }

    /** Called within the H9 transaction after its version lock, before storing the new configuration. */
    public void configure(Map<String, Object> before, Map<String, Object> next, long now) {
        if (FIELDS.stream().allMatch(key -> "homeDisplayEnabled".equals(key)
                ? java.util.Objects.equals(before.get(key), next.get(key))
                : number(before, key).compareTo(number(next, key)) == 0)) return;
        HomeDisplayBatch old = stored();
        if (old != null && old.needsExtension(now)) old = extend(old, before);
        if (old != null) {
            int i = old.index(now);
            if (number(before, "homeOnlineCurrent").compareTo(number(next, "homeOnlineCurrent")) == 0)
                next.put("homeOnlineCurrent", old.online()[i]);
            if (number(before, "homePaidCurrent").compareTo(number(next, "homePaidCurrent")) == 0)
                next.put("homePaidCurrent", BigDecimal.valueOf(old.paidCents()[i], 2));
        }
        next.put("homeDisplayAnchorAt", now);
        persist(create(next, old == null ? 1 : old.sequence() + 1, now));
    }

    private HomeDisplayBatch create(Map<String, Object> values, long sequence, long now) {
        if (!(values.get("homeDisplayEnabled") instanceof Boolean)) throw new IllegalStateException("HOME_DISPLAY_CONFIG_UNAVAILABLE");
        boolean enabled = Boolean.TRUE.equals(values.get("homeDisplayEnabled"));
        HomeDisplayBatch batch = new HomeDisplayBatch(sequence, enabled, now,
                new long[]{number(values, "homeOnlineCurrent").longValueExact()},
                new long[]{cents(values, "homePaidCurrent")});
        return enabled ? extend(batch, values) : batch;
    }

    private HomeDisplayBatch extend(HomeDisplayBatch batch, Map<String, Object> values) {
        return batch.extend(clock.millis(), number(values, "homeOnlineStepMin").longValueExact(),
                number(values, "homeOnlineStepMax").longValueExact(), cents(values, "homePaidStepMin"),
                cents(values, "homePaidStepMax"), (min, max) -> ThreadLocalRandom.current().nextLong(min, max));
    }

    private HomeDisplayBatch stored() { return config.activeValue(STATE_KEY).map(this::read).orElse(null); }
    private HomeDisplayBatch read(String value) {
        try { return json.readValue(value, HomeDisplayBatch.class); }
        catch (Exception ex) { throw new IllegalStateException("HOME_DISPLAY_STATE_INVALID", ex); }
    }
    private Map<String, Object> values() {
        try { return json.readValue(config.activeValue(GrowthPublicStatsService.VALUES_KEY).orElseThrow(), new TypeReference<>() {}); }
        catch (Exception ex) { throw new IllegalStateException("HOME_DISPLAY_CONFIG_UNAVAILABLE", ex); }
    }
    private static BigDecimal number(Map<String, Object> values, String key) { return new BigDecimal(String.valueOf(values.getOrDefault(key, 0))); }
    private static long cents(Map<String, Object> values, String key) { return number(values, key).movePointRight(2).longValueExact(); }
    private void persist(HomeDisplayBatch batch) {
        try { config.upsertAdminValue(STATE_KEY, json.writeValueAsString(batch), "JSON", "growth", "H9 shared random display batch"); }
        catch (Exception ex) { throw new IllegalStateException("HOME_DISPLAY_SAVE_FAILED", ex); }
        publishAfterCommit(batch);
    }
    private void publishAfterCommit(HomeDisplayBatch batch) {
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override public void afterCommit() {
                try { redis.execute(CACHE_WRITE, List.of(CACHE_KEY), json.writeValueAsString(new CachedBatch(batch, clock.millis() + 60_000)), String.valueOf(batch.sequence())); }
                catch (Exception ex) { LOG.warn("Home display cache refresh deferred; durable batch {} retained", batch.sequence()); }
            }
        });
    }
}
