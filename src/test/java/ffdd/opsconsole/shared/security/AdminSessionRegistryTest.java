package ffdd.opsconsole.shared.security;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
import java.time.Instant;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.*;
import org.springframework.data.redis.core.script.RedisScript;

class AdminSessionRegistryTest {
    private final StringRedisTemplate redis=mock(StringRedisTemplate.class);
    private final HashOperations<String,Object,Object> hashes=mock(HashOperations.class);
    private final SetOperations<String,String> sets=mock(SetOperations.class);
    private final AdminSessionRegistry sessions=new AdminSessionRegistry(redis);
    AdminSessionRegistryTest() {when(redis.opsForHash()).thenReturn(hashes);when(redis.opsForSet()).thenReturn(sets);}
    private void legacyActive() {
        when(hashes.multiGet(anyString(),anyList())).thenReturn(List.of("7",Instant.now().minusSeconds(86400).toString(),Instant.now().minusSeconds(10).toString()));
    }
    @Test void createUsesIdleTtlForSessionAndIndex() {
        String sid=sessions.createSession(7L,"synthetic");
        verify(redis).expire("ops:admin:session:"+sid,Duration.ofMinutes(60));
        verify(redis).expire("ops:admin:sessions:7",Duration.ofMinutes(65));
    }
    @Test void normalReadAndEnumerationUseNonTouchAtomicCheck() {
        legacyActive();when(sets.members("ops:admin:sessions:7")).thenReturn(java.util.Set.of("sid"));
        when(redis.execute(any(RedisScript.class),anyList(),any(Object[].class))).thenAnswer(invocation->{
            Object[] args=(Object[]) invocation.getRawArguments()[2];
            assertThat(args[7]).isEqualTo("0");assertThat(args[3]).isEqualTo("3600");return 1L;
        });
        assertThat(sessions.isSessionActive(7L,"sid")).isTrue();assertThat(sessions.countActiveSessions(7L)).isEqualTo(1);
        verify(hashes,never()).put(anyString(),any(),any());verify(redis,never()).expire(anyString(),any(Duration.class));
    }
    @Test void activityUsesOneAtomicScriptAndNeverWritesHashOutsideIt() {
        legacyActive();when(redis.execute(any(RedisScript.class),anyList(),any(Object[].class))).thenAnswer(invocation->{
            Object[] args=(Object[]) invocation.getRawArguments()[2];assertThat(args[7]).isEqualTo("1");
            RedisScript<?> script=invocation.getArgument(0);String lua=script.getScriptAsString();
            assertThat(lua).contains("now - seen >= ttl * 1000","SISMEMBER","ttl + 300","redis.call('TIME')");
            assertThat(lua.indexOf("'adminId'")).isLessThan(lua.indexOf("'HSET'"));
            return 0L; // deletion/revocation won the race after the metadata read
        });
        assertThat(sessions.recordActivity(7L,"sid")).isFalse();
        verify(hashes,never()).put(anyString(),any(),any());verify(hashes,never()).putAll(anyString(),anyMap());
        verify(redis,never()).expire(anyString(),any(Duration.class));
    }
    @Test void partialOrUnownedHashFailsClosedWithoutAnyScript() {
        when(hashes.multiGet(anyString(),anyList())).thenReturn(java.util.Arrays.asList("7",null,null));
        assertThat(sessions.recordActivity(7L,"sid")).isFalse();
        verify(redis,never()).execute(any(RedisScript.class),anyList(),any(Object[].class));
        assertThat(sessions.recordActivity(7L,null)).isFalse();assertThat(sessions.recordActivity(null,"sid")).isFalse();
    }
}
