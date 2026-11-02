package ffdd.opsconsole.device.compute.v2;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.*;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;

/** Atomic ownership and opaque snapshots. HINCRBY is the only operation used for monetary totals. */
public class ComputeV2RedisStore {
    private final StringRedisTemplate redis;
    private final ObjectMapper json;
    private final ComputeV2Properties config;
    private final DefaultRedisScript<String> script = new DefaultRedisScript<>();
    private final DefaultRedisScript<List> reserveScript = new DefaultRedisScript<>();

    public ComputeV2RedisStore(StringRedisTemplate redis, ObjectMapper json, ComputeV2Properties config) {
        this.redis = redis; this.json = json; this.config = config;
        script.setLocation(new ClassPathResource("compute-v2/runtime.lua")); script.setResultType(String.class);
        reserveScript.setLocation(new ClassPathResource("compute-v2/runtime.lua")); reserveScript.setResultType(List.class);
    }

    public String key(String suffix) { return config.prefix() + suffix; }
    public String value(String suffix) { return redis.opsForValue().get(key(suffix)); }
    public void cache(String suffix,String value) { redis.opsForValue().setIfAbsent(key(suffix),value,java.time.Duration.ofSeconds(config.getDedupRetentionSeconds())); }
    public String execute(String... args) { return redis.execute(script, List.of(config.prefix()), (Object[]) args); }
    public List<?> reserve(String... args) { return redis.execute(reserveScript, List.of(config.prefix()), (Object[]) args); }
    public String field(String hash, String id) { return (String) redis.opsForHash().get(key(hash), id); }
    public void field(String hash, String id, String value) { redis.opsForHash().put(key(hash), id, value); }
    public Map<Object,Object> entries(String hash) { return redis.opsForHash().entries(key(hash)); }
    public Set<String> sorted(String name) { return redis.opsForZSet().range(key(name),0,-1); }
    public Set<String> members(String name) { return redis.opsForSet().members(key(name)); }
    public long setSize(String name) { return Objects.requireNonNull(redis.opsForSet().size(key(name))); }
    public Double score(String name, String member) { return redis.opsForZSet().score(key(name),member); }
    public void delete(String name) { redis.delete(key(name)); }
    public void removeSorted(String name, String value) { redis.opsForZSet().remove(key(name),value); }
    public boolean ready() { return "1".equals(redis.opsForValue().get(key("ready"))); }
    public boolean initialized() { return redis.hasKey(key("last-freeze")); }
    public boolean persisted(String eventId) { String value = value("seen:"+eventId); return value != null && value.startsWith("persisted:"); }
    public void ready(boolean ready) { redis.opsForValue().set(key("ready"),ready ? "1" : "0"); }
    public String encode(Object value) {
        try { return json.writeValueAsString(value); }
        catch (JsonProcessingException ex) { throw new IllegalArgumentException("Invalid compute snapshot",ex); }
    }
    public <T> T decode(String value, Class<T> type) {
        try { return json.readValue(value,type); }
        catch (JsonProcessingException ex) { throw new IllegalStateException("Corrupt compute snapshot",ex); }
    }
    public void apply(ComputeV2Event event, Instant receivedAt) {
        String payload = encode(event);
        execute("apply",event.eventId(),event.taskNo(),Long.toString(event.generation()),event.kind().name(),
                payload,Long.toString(receivedAt.toEpochMilli()),Long.toString(event.task().issuedAt().toEpochMilli()),
                Integer.toString(config.getUnpersistedCapacity()),Integer.toString(config.getDedupRetentionSeconds()),
                Long.toString(event.userId()),Long.toString(event.kind() == ComputeV2Event.Kind.VERIFIED ? event.task().rewardUsdtMicros() : 0),
                Long.toString(event.evidence() == null ? 0 : event.evidence().rewardNexMicros()),encode(event.task()),digest(payload),
                Long.toString(windowEnd(event.userId(),receivedAt).getEpochSecond()),Long.toString(event.deviceId()),
                Long.toString(event.task().leaseExpiresAt().toEpochMilli()));
        if (event.evidence() != null && event.evidence().rewardNexMicros() > 0)
            daily(event.evidence().dailyEntitlementKey(),event.eventId());
    }
    public boolean daily(String entitlement, String eventId) {
        return "1".equals(execute("daily",entitlement,eventId,Integer.toString(config.getDedupRetentionSeconds())));
    }
    public void restore(ComputeV2Event event) {
        restore(event,false);
    }
    private void restore(ComputeV2Event event,boolean persisted) {
        execute("restore",event.taskNo(),Long.toString(event.deviceId()),Long.toString(event.userId()),
                Long.toString(event.generation()),event.kind().name(),encode(event.task()),encode(event),
                Long.toString(event.task().issuedAt().toEpochMilli()),
                Long.toString(event.task().leaseExpiresAt().toEpochMilli()),event.task().requestId(),
                Integer.toString(persisted ? config.getPostedDedupSeconds() : config.getDedupRetentionSeconds()),persisted ? "1" : "0");
        if (event.evidence() != null && event.evidence().rewardNexMicros() > 0)
            daily(event.evidence().dailyEntitlementKey(),event.eventId());
    }
    public void restorePersisted(ComputeV2Event event) {
        restore(event,true);
        markPersisted(List.of(event));
    }
    public void markPersisted(Collection<ComputeV2Event> events) {
        List<String> args = new ArrayList<>(List.of("persisted",Integer.toString(config.getPostedDedupSeconds())));
        for (var e : events) {
            args.add(e.eventId()); args.add(Long.toString(e.userId()));
            args.add(Long.toString(e.kind() == ComputeV2Event.Kind.VERIFIED ? e.task().rewardUsdtMicros() : 0));
            args.add(Long.toString(e.evidence() == null ? 0 : e.evidence().rewardNexMicros()));
            args.add(e.taskNo());
            args.add(e.kind() == ComputeV2Event.Kind.VERIFIED ? "1" : "0");
            args.add(digest(encode(e)));
            args.add(e.kind().name()); args.add(e.userId()+":"+e.deviceId()+":"+e.task().requestId());
        }
        execute(args.toArray(String[]::new));
        events.stream().map(ComputeV2Event::taskNo).distinct().forEach(task -> execute("prune",task,Integer.toString(config.getPostedDedupSeconds())));
    }
    private static String digest(String value) {
        try { return HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(value.getBytes(java.nio.charset.StandardCharsets.UTF_8))); }
        catch (java.security.NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }
    public static Instant windowEnd(long userId,Instant receivedAt) {
        int offset = Math.floorMod(Long.hashCode(userId),60);
        return Instant.ofEpochSecond(Math.floorDiv(receivedAt.getEpochSecond()-offset,60)*60+60+offset);
    }
}
