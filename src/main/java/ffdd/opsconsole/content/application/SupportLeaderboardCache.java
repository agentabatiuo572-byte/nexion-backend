package ffdd.opsconsole.content.application;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import ffdd.opsconsole.content.domain.SupportLeaderboard;
import ffdd.opsconsole.content.domain.SupportLeaderboard.*;
import ffdd.opsconsole.shared.exception.BizException;
import java.time.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;

/** Only completed, system-owned publications; never stores an authenticated response. */
@Service
public class SupportLeaderboardCache {
    static final String KEY="uvel:support:leaderboard:v1:snapshot", LOCK="uvel:support:leaderboard:v1:lock";
    static final Duration LEASE=Duration.ofMinutes(10);
    static final DefaultRedisScript<Long> PUBLISH=new DefaultRedisScript<>("""
        if redis.call('GET', KEYS[1]) ~= ARGV[1] then return 0 end
        redis.call('SET', KEYS[2], ARGV[2])
        return 1
        """,Long.class);
    private final StringRedisTemplate redis;
    private final ObjectMapper json=new ObjectMapper().registerModule(new JavaTimeModule())
        .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES,DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
    private final AtomicReference<Decoded> decoded=new AtomicReference<>();
    private record Decoded(String payload,Generation generation) { }
    public record Entry(Publication publication,boolean refreshFailed) { }
    public record Generation(Map<String,Entry> entries) {
        public Generation { entries=Map.copyOf(entries); }
        public List<YearMonth> months(Context selected) {
            var months=new TreeSet<YearMonth>();
            for(Entry entry:entries.values()) {Context c=entry.publication().snapshot().context();
                if(c.rankMonth()!=null && c.board()==selected.board() && c.currency().equals(selected.currency())
                    && c.scope()==selected.scope() && c.approvedGroupIds().equals(selected.approvedGroupIds())
                    && c.definitionVersion().equals(selected.definitionVersion()))months.add(c.rankMonth());}
            return List.copyOf(months.descendingSet());
        }
    }
    public SupportLeaderboardCache(StringRedisTemplate redis){this.redis=redis;}
    public Generation current() {
        final String payload;
        try {payload=redis.opsForValue().get(KEY);}catch(DataAccessException ex){throw unavailable();}
        if(payload==null)throw new BizException(503,"SUPPORT_LEADERBOARD_CACHE_NOT_READY");
        Decoded previous=decoded.get();if(previous!=null && previous.payload().equals(payload))return previous.generation();
        try {
            Generation generation=json.readValue(payload,Generation.class);
            for(var entry:generation.entries().entrySet()) {
                Snapshot s=entry.getValue().publication().snapshot();
                if(!entry.getKey().equals(SupportLeaderboardPublicationService.streamKey(s.context()))
                    || !SupportLeaderboardPolicy.DEFINITION.equals(s.context().definitionVersion())
                    || !SupportLeaderboard.validCachedSnapshot(s))throw new IllegalArgumentException("Invalid cached publication");
            }
            decoded.set(new Decoded(payload,generation));return generation;
        }catch(java.io.IOException | IllegalArgumentException | NullPointerException ex){throw new BizException(503,"SUPPORT_LEADERBOARD_CACHE_INVALID");}
    }
    public String tryLock() {
        String token=UUID.randomUUID().toString();
        try{return Boolean.TRUE.equals(redis.opsForValue().setIfAbsent(LOCK,token,LEASE))?token:null;}
        catch(DataAccessException ex){throw unavailable();}
    }
    public boolean publish(String token,Generation generation) {
        try {
            String payload=json.writeValueAsString(generation);
            boolean published=Long.valueOf(1).equals(redis.execute(PUBLISH,List.of(LOCK,KEY),token,payload));
            if(published)decoded.set(new Decoded(payload,generation));return published;
        }catch(java.io.IOException ex){throw new IllegalArgumentException("Invalid cache generation",ex);}
        catch(DataAccessException ex){throw unavailable();}
    }
    public void release(String token) {
        try{redis.execute(SupportAnalyticsRedisStore.RELEASE,List.of(LOCK),token);}
        catch(DataAccessException ex){throw unavailable();}
    }
    private static BizException unavailable(){return new BizException(503,"SUPPORT_LEADERBOARD_CACHE_UNAVAILABLE");}
}
