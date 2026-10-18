package ffdd.opsconsole.team.application;

import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
import ffdd.opsconsole.team.mapper.DirectReferralMapper;
import java.time.*;
import org.junit.jupiter.api.Test;

class DirectReferralVietnamPeriodTest {
    @Test void v1AndV2UseTheSameVersionedWindowWithStorageTimestampUpperBound() {
        var mapper=mock(DirectReferralMapper.class);
        var policy=mock(DirectReferralPolicyService.class);
        when(policy.scope()).thenReturn(new DirectReferralPolicyService.Scope("PRODUCTION",null,0));
        when(mapper.activeUser(42L,0)).thenReturn(42L);
        var instant=Instant.parse("2026-10-18T16:30:00Z");
        var service=new DirectReferralService(mapper,policy,null,null,null,null,null,null,Clock.fixed(instant,ZoneOffset.UTC));
        service.insightsV2(42L,"week",1,20,null,"all");
        verify(mapper).sums(42L,"PRODUCTION",null,LocalDateTime.parse("2026-10-19T00:30:00"),LocalDateTime.parse("2026-10-12T00:00:00"));
        verify(mapper).filteredSums(42L,"PRODUCTION",null,LocalDateTime.parse("2026-10-19T00:30:00"),LocalDateTime.parse("2026-10-12T00:00:00"),null);
        var next=new DirectReferralService(mapper,policy,null,null,null,null,null,null,Clock.fixed(Instant.parse("2026-10-18T17:00:00Z"),ZoneOffset.UTC));
        next.insights(42L,"week",1,20,null);
        verify(mapper).sums(42L,"PRODUCTION",null,LocalDateTime.parse("2026-10-19T01:00:00"),LocalDateTime.parse("2026-10-19T01:00:00"));
    }
}
