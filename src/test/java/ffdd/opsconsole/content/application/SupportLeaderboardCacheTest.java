package ffdd.opsconsole.content.application;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import ffdd.opsconsole.content.domain.SupportLeaderboard;
import ffdd.opsconsole.content.domain.SupportLeaderboard.*;
import ffdd.opsconsole.shared.exception.BizException;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class SupportLeaderboardCacheTest {
    final StringRedisTemplate redis=mock(StringRedisTemplate.class);
    @SuppressWarnings("unchecked") final ValueOperations<String,String> values=mock(ValueOperations.class);
    final SupportLeaderboardCache cache=new SupportLeaderboardCache(redis);
    final ObjectMapper json=new ObjectMapper().registerModule(new JavaTimeModule());
    SupportLeaderboardCacheTest(){when(redis.opsForValue()).thenReturn(values);}
    SupportLeaderboardCache.Generation generation(){
        var context=SupportLeaderboardServiceTest.context(Board.netContribution,"USDT",SupportLeaderboardServiceTest.NOW.minusSeconds(30));
        var snapshot=SupportLeaderboard.calculate(context,"cache-fixture",Coverage.COMPLETE,List.of(SupportLeaderboardServiceTest.candidate(context,7,2)));
        return new SupportLeaderboardCache.Generation(Map.of(SupportLeaderboardPublicationService.streamKey(context),new SupportLeaderboardCache.Entry(new Publication(1,SupportLeaderboardServiceTest.NOW,snapshot),false)));
    }
    @Test void completePayloadIsDecodedOnceAndUnchangedPayloadReusesImmutableGeneration() throws Exception {
        String payload=json.writeValueAsString(generation());when(values.get(SupportLeaderboardCache.KEY)).thenReturn(payload);
        var first=cache.current();assertSame(first,cache.current());assertEquals(1,first.entries().size());
    }
    @Test void validatedLegacySnapshotKeepsItsDigestAndIsIndexedForCurrentUvtWhileCorruptionStillFails() throws Exception {
        var context=SupportLeaderboardServiceTest.context(Board.netContribution,"NEX",SupportLeaderboardServiceTest.NOW.minusSeconds(30));
        var snapshot=SupportLeaderboard.calculate(context,"legacy-cache",Coverage.COMPLETE,List.of(SupportLeaderboardServiceTest.candidate(context,7,2)));
        var old=new SupportLeaderboardCache.Generation(Map.of(SupportLeaderboardPublicationService.streamKey(context),new SupportLeaderboardCache.Entry(new Publication(1,SupportLeaderboardServiceTest.NOW,snapshot),false)));
        String payload=json.writeValueAsString(old);when(values.get(anyString())).thenReturn(payload);
        var current=SupportLeaderboardServiceTest.context(Board.netContribution,"UVT",SupportLeaderboardServiceTest.NOW);
        var view=cache.current();assertEquals(snapshot,view.entries().get(SupportLeaderboardPublicationService.streamKey(current)).publication().snapshot());
        assertEquals(List.of(YearMonth.of(2026,10)),view.months(current));
        when(values.get(anyString())).thenReturn(payload.replace("legacy-cache","tampered-cache"));
        assertEquals("SUPPORT_LEADERBOARD_CACHE_INVALID",assertThrows(BizException.class,cache::current).getMessage());
    }
    @Test void absentAndRedisOutageFailClosed(){assertEquals("SUPPORT_LEADERBOARD_CACHE_NOT_READY",assertThrows(BizException.class,cache::current).getMessage());
        when(values.get(anyString())).thenThrow(new DataAccessResourceFailureException("down"));assertEquals("SUPPORT_LEADERBOARD_CACHE_UNAVAILABLE",assertThrows(BizException.class,cache::current).getMessage());}
    @Test void digestExcludedFlagsAndWrongStreamAreRejected() throws Exception {
        String payload=json.writeValueAsString(generation());
        for(String broken:List.of(payload.replace("\"state\":\"COMPLETE\"","\"state\":\"PROVISIONAL\""),
            payload.replace("\"rankMetricCoverage\":\"COMPLETE\"","\"rankMetricCoverage\":\"UNKNOWN\""),
            payload.replaceFirst("\"comparisonKey\":\"[a-f0-9]{64}\"","\"comparisonKey\":\""+"0".repeat(64)+"\""))) {
            assertNotEquals(payload,broken);when(values.get(anyString())).thenReturn(broken);
            assertEquals("SUPPORT_LEADERBOARD_CACHE_INVALID",assertThrows(BizException.class,cache::current).getMessage());
        }
    }
    @Test void lockUsesNxLeaseAndLostTokenCannotPublish(){when(values.setIfAbsent(eq(SupportLeaderboardCache.LOCK),anyString(),eq(Duration.ofMinutes(10)))).thenReturn(true);
        String token=cache.tryLock();assertNotNull(token);when(redis.execute(eq(SupportLeaderboardCache.PUBLISH),anyList(),eq(token),anyString())).thenReturn(0L);
        assertFalse(cache.publish(token,generation()));verify(values).setIfAbsent(eq(SupportLeaderboardCache.LOCK),eq(token),eq(Duration.ofMinutes(10)));
        when(values.setIfAbsent(anyString(),anyString(),any(Duration.class))).thenReturn(false);assertNull(cache.tryLock());
    }
    @Test void readPathCannotReferenceStatisticsOrPersistenceCompiler() throws Exception {
        String source=java.nio.file.Files.readString(java.nio.file.Path.of("src/main/java/ffdd/opsconsole/content/application/SupportLeaderboardService.java"));
        for(String forbidden:List.of("SupportLeaderboard.calculate","readForAuthorizedLeaderboard","publications.latest","publications.publish","sourceMapper"))assertFalse(source.contains(forbidden),forbidden);
    }
}
