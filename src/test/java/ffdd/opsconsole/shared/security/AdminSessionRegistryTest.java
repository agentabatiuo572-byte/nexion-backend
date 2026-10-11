package ffdd.opsconsole.shared.security;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
import java.time.Instant;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.mock.env.MockPropertySource;
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
            assertThat(args[7]).isEqualTo("0");assertThat(args[3]).isEqualTo("3600");
            assertThat(args[8]).isEqualTo("0");return 1L;
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

    private AdminSessionRegistry configuredSessions(String flag) {
        try (var context = new AnnotationConfigApplicationContext()) {
            if (flag != null) context.getEnvironment().getPropertySources().addFirst(
                    new MockPropertySource().withProperty("NEXION_ADMIN_SESSION_IDLE_DISABLED", flag));
            context.registerBean(StringRedisTemplate.class, () -> redis);
            context.register(AdminSessionRegistry.class);
            context.refresh();
            return context.getBean(AdminSessionRegistry.class);
        }
    }

    @Test void absentOrFalseFlagKeepsTheExistingIdleTtl() {
        for (String flag : java.util.Arrays.asList(null, "false")) {
            clearInvocations(redis);
            String sid = configuredSessions(flag).createSession(7L, "synthetic");
            verify(redis).expire("ops:admin:session:" + sid, Duration.ofMinutes(60));
            verify(redis).expire("ops:admin:sessions:7", Duration.ofMinutes(65));
            verify(redis, never()).persist(anyString());
        }
    }

    @Test void disabledIdleCreatesSessionWithoutTtlAndClearsExistingIndexTtl() {
        String sid = configuredSessions("true").createSession(7L, "synthetic");
        verify(hashes).putAll(eq("ops:admin:session:" + sid), anyMap());
        verify(sets).add("ops:admin:sessions:7", sid);
        verify(redis, never()).expire(anyString(), any(Duration.class));
        verify(redis).persist("ops:admin:sessions:7");
    }

    @Test void disabledIdleUsesAtomicPersistenceForBothReadsAndActivity() {
        var disabled = configuredSessions("true");
        when(hashes.multiGet(anyString(), anyList())).thenReturn(List.of("7",
                Instant.now().minusSeconds(86400).toString(), Instant.now().minusSeconds(7200).toString()));
        when(redis.execute(any(RedisScript.class), anyList(), any(Object[].class))).thenAnswer(invocation -> {
            Object[] args = (Object[]) invocation.getRawArguments()[2];
            assertThat(args).hasSizeGreaterThanOrEqualTo(9);
            assertThat(args[8]).isEqualTo("1");
            RedisScript<?> script = invocation.getArgument(0);
            String lua = script.getScriptAsString();
            assertThat(lua).contains("redis.call('PERSIST', KEYS[1])", "redis.call('PERSIST', KEYS[2])",
                    "not idleDisabled and now - seen >= ttl * 1000", "if not idleDisabled then",
                    "'issuedAt') ~= ARGV[10]", "not redis.call('HGET', KEYS[1], 'lastSeenAt')");
            assertThat(lua.indexOf("seen > now")).isLessThan(lua.indexOf("'PERSIST'"));
            assertThat(lua.indexOf("SISMEMBER")).isLessThan(lua.indexOf("'PERSIST'"));
            return 1L;
        });
        assertThat(disabled.isSessionActive(7L, "sid")).isTrue();
        assertThat(disabled.recordActivity(7L, "sid")).isTrue();
        verify(redis, never()).persist(anyString());
        verify(redis, never()).expire(anyString(), any(Duration.class));
        verify(hashes, never()).putAll(anyString(), anyMap());
    }

    @Test void disabledIdleStillRejectsMissingIncompleteAndUnownedSessionsBeforeTheScript() {
        var disabled = configuredSessions("true");
        for (List<Object> metadata : List.<List<Object>>of(java.util.Arrays.asList(null, null, null),
                java.util.Arrays.asList("7", null, null),
                List.of("8", Instant.now().toString(), Instant.now().toString()),
                List.of("7", "invalid-issued-at", Instant.now().toString()),
                List.of("7", Instant.now().toString(), "invalid-last-seen"))) {
            when(hashes.multiGet(anyString(), anyList())).thenReturn(metadata);
            assertThat(disabled.isSessionActive(7L, "sid")).isFalse();
            assertThat(disabled.recordActivity(7L, "sid")).isFalse();
        }
        verify(redis, never()).execute(any(RedisScript.class), anyList(), any(Object[].class));
        verify(redis, never()).persist(anyString());
        verify(hashes, never()).putAll(anyString(), anyMap());
    }

    @Test void disabledIdleDoesNotRecreateARevokedSessionWhenRevocationWinsTheSnapshotRace() {
        var disabled = configuredSessions("true");
        legacyActive();
        when(redis.execute(any(RedisScript.class), anyList(), any(Object[].class))).thenAnswer(invocation -> {
            Object[] args = (Object[]) invocation.getRawArguments()[2];
            assertThat(args).hasSizeGreaterThanOrEqualTo(9);
            assertThat(args[8]).isEqualTo("1");
            return 0L; // The atomic identity/index check rejects deletion after multiGet.
        });
        assertThat(disabled.isSessionActive(7L, "sid")).isFalse();
        assertThat(disabled.recordActivity(7L, "sid")).isFalse();
        verify(hashes, never()).putAll(anyString(), anyMap());
        verify(redis, never()).persist(anyString());
        verify(redis, never()).expire(anyString(), any(Duration.class));
    }
}
