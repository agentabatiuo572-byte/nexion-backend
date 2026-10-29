package ffdd.opsconsole.content.application;

import ffdd.opsconsole.shared.exception.BizException;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;

/** One complete statistics generation; contains no authenticated operator response. */
@Service
public class SupportAnalyticsRedisStore {
    static final String SNAPSHOT_KEY="uvel:support:analytics:v1:snapshot";
    static final String LOCK_KEY="uvel:support:analytics:v1:lock";
    static final Duration LOCK_LEASE=Duration.ofSeconds(120);
    static final DefaultRedisScript<Long> PUBLISH=new DefaultRedisScript<>("""
        if redis.call('GET', KEYS[1]) ~= ARGV[1] then return 0 end
        redis.call('SET', KEYS[2], ARGV[2])
        redis.call('DEL', KEYS[1])
        return 1
        """,Long.class);
    static final DefaultRedisScript<Long> RELEASE=new DefaultRedisScript<>("""
        if redis.call('GET', KEYS[1]) ~= ARGV[1] then return 0 end
        return redis.call('DEL', KEYS[1])
        """,Long.class);
    private final StringRedisTemplate redis;
    public SupportAnalyticsRedisStore(StringRedisTemplate redis) {this.redis=redis;}

    public String read() {
        try {return redis.opsForValue().get(SNAPSHOT_KEY);}
        catch(DataAccessException failure) {throw unavailable();}
    }
    public String tryLock() {
        String token=UUID.randomUUID().toString();
        try {return Boolean.TRUE.equals(redis.opsForValue().setIfAbsent(LOCK_KEY,token,LOCK_LEASE))?token:null;}
        catch(DataAccessException failure) {throw unavailable();}
    }
    public boolean publish(String token,String payload) {
        if(token==null || token.isBlank() || payload==null || payload.isBlank())throw new IllegalArgumentException("COMPLETE_STATISTICS_GENERATION_REQUIRED");
        try {return Long.valueOf(1).equals(redis.execute(PUBLISH,List.of(LOCK_KEY,SNAPSHOT_KEY),token,payload));}
        catch(DataAccessException failure) {throw unavailable();}
    }
    public void release(String token) {
        if(token==null)return;
        try {redis.execute(RELEASE,List.of(LOCK_KEY),token);}
        catch(DataAccessException failure) {throw unavailable();}
    }
    private static BizException unavailable() {return new BizException(503,"SUPPORT_ANALYTICS_CACHE_UNAVAILABLE");}
}
