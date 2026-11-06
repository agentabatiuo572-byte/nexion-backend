package ffdd.opsconsole.platform.application;

import ffdd.opsconsole.platform.facade.PlatformConfigFacade.SessionLifetimes;
import ffdd.opsconsole.platform.mapper.PlatformConfigItemMapper;
import java.sql.Connection;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

@Component
@RequiredArgsConstructor
@Slf4j
class SessionLifetimeCache {
    private static final String KEY = "nexion:auth:session-lifetimes:v1";
    private static final Duration TTL = Duration.ofSeconds(30);
    private static final DefaultRedisScript<Long> FILL = new DefaultRedisScript<>("""
            local current = redis.call('GET', KEYS[1]) or ''
            if current ~= ARGV[1] then return 0 end
            redis.call('SET', KEYS[1], ARGV[2], 'EX', ARGV[3])
            return 1
            """, Long.class);

    private final PlatformConfigItemMapper mapper;
    private final StringRedisTemplate redis;

    SessionLifetimes read() {
        String observed;
        try {
            observed = redis.opsForValue().get(KEY);
        } catch (DataAccessException ex) {
            return load();
        }
        if (observed != null) {
            String[] values = observed.split(",", -1);
            if (values.length == 2) return SessionLifetimes.from(values[0], values[1]);
        }
        SessionLifetimes fresh = load();
        // An older repeatable-read view must never seed the shared cache.
        if (!TransactionSynchronizationManager.isActualTransactionActive()
                || Integer.valueOf(Connection.TRANSACTION_READ_COMMITTED)
                        .equals(TransactionSynchronizationManager.getCurrentTransactionIsolationLevel())) {
            try {
                redis.execute(FILL, List.of(KEY), observed == null ? "" : observed,
                        fresh.accessHours() + "," + fresh.refreshDays(), Long.toString(TTL.toSeconds()));
            } catch (DataAccessException ex) {
                log.debug("Session lifetime cache fill unavailable", ex);
            }
        }
        return fresh;
    }

    private SessionLifetimes load() {
        String access = null;
        String refresh = null;
        for (var item : mapper.selectSessionLifetimes()) {
            if (SessionLifetimes.ACCESS_KEY.equals(item.getConfigKey())) access = item.getConfigValue();
            if (SessionLifetimes.REFRESH_KEY.equals(item.getConfigKey())) refresh = item.getConfigValue();
        }
        return SessionLifetimes.from(access, refresh);
    }

    void invalidateAfterCommit(String configKey) {
        if (!SessionLifetimes.ACCESS_KEY.equals(configKey) && !SessionLifetimes.REFRESH_KEY.equals(configKey)) return;
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override public void afterCommit() {
                    invalidate();
                }
            });
        } else {
            invalidate();
        }
    }

    private void invalidate() {
        try {
            // The marker also prevents an in-flight read of the old settings from filling after this write.
            redis.opsForValue().set(KEY, "!" + UUID.randomUUID(), TTL);
        } catch (DataAccessException ex) {
            log.warn("Session lifetime cache invalidation failed, entry expires within {} seconds", TTL.toSeconds());
        }
    }
}
