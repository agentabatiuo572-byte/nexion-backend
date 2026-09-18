package ffdd.opsconsole.bi.application;

import static org.mockito.Mockito.*;
import ffdd.opsconsole.bi.mapper.L6BehaviorEvidenceMapper;
import java.util.List;
import org.junit.jupiter.api.Test;

class L6BehaviorEvidenceSchedulerTest {
    @Test void failedEvidenceIsDeferredWithoutPreventingLaterValidRows() {
        var mapper=mock(L6BehaviorEvidenceMapper.class);var service=mock(L6BehaviorEvidenceService.class);
        when(mapper.eligible(100)).thenReturn(List.of(1L,2L,3L,4L));
        when(service.consume(1)).thenThrow(new L6BehaviorEvidenceService.EvidenceRejected("L6_EVIDENCE_FACT_CONFLICT"));
        when(service.consume(2)).thenThrow(new RuntimeException("secret database value"));
        when(service.consume(3)).thenReturn(false);when(service.consume(4)).thenReturn(true);
        new L6BehaviorEvidenceScheduler(mapper,service).dispatchPending();
        verify(mapper).eligible(100);
        verify(mapper).defer(1,"L6_EVIDENCE_FACT_CONFLICT");verify(mapper).defer(2,"L6_EVIDENCE_VERIFICATION_UNAVAILABLE");
        verify(service).consume(4);verifyNoMoreInteractions(mapper);
    }
}
