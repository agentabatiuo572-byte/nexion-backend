package ffdd.opsconsole.team.application;

import static org.mockito.Mockito.*;
import ffdd.opsconsole.team.mapper.LeadershipPoolAlertEvidenceMapper;
import java.util.List;
import org.junit.jupiter.api.Test;

class LeadershipPoolAlertEvidenceSchedulerTest {
    @Test void failedEvidenceIsDeferredWithoutPreventingLaterValidRows() {
        var mapper=mock(LeadershipPoolAlertEvidenceMapper.class);var service=mock(LeadershipPoolAlertEvidenceService.class);
        when(mapper.eligible(100)).thenReturn(List.of(1L,2L,3L,4L));
        when(service.consume(1)).thenThrow(new LeadershipPoolAlertEvidenceService.EvidenceRejected("F4_ALERT_AUDIT_CONFLICT"));
        when(service.consume(2)).thenThrow(new RuntimeException("secret database value"));
        when(service.consume(3)).thenReturn(false);when(service.consume(4)).thenReturn(true);
        new LeadershipPoolAlertEvidenceScheduler(mapper,service).dispatchPending();
        verify(mapper).eligible(100);
        verify(mapper).defer(1,"F4_ALERT_AUDIT_CONFLICT");verify(mapper).defer(2,"F4_ALERT_VERIFICATION_UNAVAILABLE");
        verify(service).consume(4);verifyNoMoreInteractions(mapper);
    }
}
