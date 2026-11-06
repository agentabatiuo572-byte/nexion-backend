package ffdd.opsconsole.content.realtime;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PreDestroy;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.event.EventListener;
import org.springframework.core.env.Environment;
import org.springframework.data.domain.Range;
import org.springframework.data.redis.connection.Limit;
import org.springframework.data.redis.core.StreamOperations;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

@Slf4j
@Component
class ConversationSocketRedis {
    private static final DefaultRedisScript<String> APPEND = new DefaultRedisScript<>(
            "return redis.call('XADD', KEYS[1], 'MAXLEN', '~', 4096, '*', 'notice', ARGV[1])", String.class);
    private static final DefaultRedisScript<Long> LEASE = new DefaultRedisScript<>("""
            redis.call('ZREMRANGEBYSCORE', KEYS[1], '-inf', ARGV[1])
            redis.call('ZADD', KEYS[1], ARGV[2], ARGV[3])
            return redis.call('PEXPIRE', KEYS[1], ARGV[4])
            """, Long.class);
    private static final DefaultRedisScript<List> PRESENCE = new DefaultRedisScript<>("""
            local online = redis.call('ZCOUNT', KEYS[1], '(' .. ARGV[1], '+inf')
            local typing = redis.call('ZREVRANGE', KEYS[2], 0, 0, 'WITHSCORES')
            return {online, tonumber(typing[2]) or 0}
            """, List.class);
    private static final long ONLINE_MILLIS = 60_000;
    private static final long TYPING_MILLIS = 5_000;

    record Delivery(String id, JsonNode notice) {}
    record DeliveryLost() {}
    record Presence(boolean online, long typingMillis) {}

    private final StringRedisTemplate redis;
    private final StreamOperations<String,String,String> stream;
    private final ObjectMapper json;
    private final ApplicationEventPublisher events;
    private final String prefix;
    private final String streamKey;
    private final String instance = UUID.randomUUID().toString();
    private final AtomicBoolean publishFailed = new AtomicBoolean();
    private final ScheduledExecutorService reader = Executors.newSingleThreadScheduledExecutor(task -> {
        Thread thread = new Thread(task, "conversation-redis-reader");
        thread.setDaemon(true);
        return thread;
    });
    private volatile long lastRead;
    private boolean interrupted;
    private String cursor;

    ConversationSocketRedis(StringRedisTemplate redis, ObjectMapper json, ApplicationEventPublisher events,
            Environment environment) {
        this.redis = redis;
        this.stream = redis.opsForStream();
        this.json = json;
        this.events = events;
        String profiles = String.join(",", environment.getActiveProfiles());
        String deployment = environment.getProperty("nexion.deployment.public-test", "false");
        this.prefix = "support:ws:v1:" + profiles + ":" + deployment + ":";
        this.streamKey = prefix + "events";
    }

    String instance() { return instance; }

    @EventListener(ApplicationReadyEvent.class)
    void start() { reader.scheduleWithFixedDelay(this::poll, 0, 250, TimeUnit.MILLISECONDS); }

    @PreDestroy void stop() { reader.shutdownNow(); }

    boolean isAvailable() {
        long observed = lastRead;
        return observed != 0 && !publishFailed.get() && System.nanoTime() - observed < TimeUnit.SECONDS.toNanos(5);
    }

    void publish(Map<String,?> notice) {
        try {
            append(notice);
        } catch (RuntimeException | JsonProcessingException ex) {
            markGap(ex);
        }
    }

    void markGap() { markGap(new IllegalStateException("Realtime outbound queue overloaded")); }

    private void markGap(Exception failure) {
        publishFailed.set(true);
        unavailable(failure);
    }

    private String append(Map<String,?> notice) throws JsonProcessingException {
        return redis.execute(APPEND, List.of(streamKey), json.writeValueAsString(notice));
    }

    private void poll() {
        try {
            if (publishFailed.getAndSet(false)) {
                try { append(Map.of("kind", "RESYNC", "source", instance)); }
                catch (RuntimeException | JsonProcessingException ex) { publishFailed.set(true); throw ex; }
            }
            if (cursor == null) {
                // Before the first socket authenticates, set a concrete cursor even on an empty stream.
                cursor = append(Map.of("kind", "START"));
            } else {
                var rows = stream.range(streamKey, Range.rightUnbounded(Range.Bound.inclusive(cursor)),
                        Limit.limit().count(257));
                if (rows.isEmpty() || !cursor.equals(rows.get(0).getId().getValue())) {
                    cursor = null;
                    unavailable(new IllegalStateException("Realtime stream cursor expired"));
                    return;
                }
                for (var row : rows.subList(1, rows.size())) {
                    String id = row.getId().getValue();
                    events.publishEvent(new Delivery(id, json.readTree(row.getValue().get("notice"))));
                    cursor = id;
                }
            }
            synchronized (this) {
                lastRead = System.nanoTime();
                interrupted = false;
            }
        } catch (RuntimeException | JsonProcessingException ex) {
            cursor = null;
            unavailable(ex);
        }
    }

    private synchronized void unavailable(Exception failure) {
        boolean wasAvailable = lastRead != 0;
        lastRead = 0;
        if (!interrupted) {
            log.warn("Conversation realtime Redis delivery interrupted ({})", failure.getClass().getSimpleName());
            interrupted = true;
        }
        if (wasAvailable) events.publishEvent(new DeliveryLost());
    }

    void touchOnline(String actor, String socketId, long now) {
        lease(onlineKey(actor), socketId, now, ONLINE_MILLIS);
    }

    void touchTyping(String actor, String no, String socketId, long now) {
        lease(typingKey(actor, no), socketId, now, TYPING_MILLIS);
    }

    private void lease(String key, String socketId, long now, long duration) {
        redis.execute(LEASE, List.of(key), Long.toString(now), Long.toString(now + duration),
                instance + ":" + socketId, Long.toString(duration));
    }

    void clearOnline(String actor, String socketId) {
        redis.opsForZSet().remove(onlineKey(actor), instance + ":" + socketId);
    }

    void clearTyping(String actor, String no, String socketId) {
        redis.opsForZSet().remove(typingKey(actor, no), instance + ":" + socketId);
    }

    Presence presence(String actor, String no, long now) {
        var state = redis.execute(PRESENCE, List.of(onlineKey(actor), typingKey(actor, no)), Long.toString(now));
        boolean online = ((Number) state.get(0)).longValue() > 0;
        long typing = online ? ((Number) state.get(1)).longValue() - now : 0;
        return new Presence(online, Math.max(0, Math.min(TYPING_MILLIS, typing)));
    }

    private String onlineKey(String actor) { return prefix + "online:" + actor; }
    private String typingKey(String actor, String no) { return prefix + "typing:" + actor + ":" + no; }
}
