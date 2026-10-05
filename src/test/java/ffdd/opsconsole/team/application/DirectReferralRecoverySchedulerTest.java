package ffdd.opsconsole.team.application;

import static org.mockito.Mockito.*;
import java.math.BigDecimal;
import java.util.List;
import java.util.stream.LongStream;
import ffdd.opsconsole.team.mapper.DirectReferralMapper;
import org.junit.jupiter.api.Test;

class DirectReferralRecoverySchedulerTest {
    @Test void blockedFirstPageDoesNotStarveLaterGroupsAndEveryScanIsScoped() {
        var mapper=mock(DirectReferralMapper.class);
        var service=mock(DirectReferralService.class);
        var policies=mock(DirectReferralPolicyService.class);
        when(policies.scope()).thenReturn(new DirectReferralPolicyService.Scope("SANDBOX","current-run",1));
        var first=LongStream.rangeClosed(1,100).mapToObj(id->new DirectReferralMapper.RecoveryRow(id,"DR-"+id,BigDecimal.ONE)).toList();
        when(mapper.pendingRecovery("SANDBOX","current-run",0)).thenReturn(first);
        when(mapper.pendingRecovery("SANDBOX","current-run",100)).thenReturn(List.of(new DirectReferralMapper.RecoveryRow(101,"DR-101",BigDecimal.ONE)));
        when(mapper.pendingRecovery("SANDBOX","current-run",101)).thenReturn(List.of());
        doThrow(new IllegalStateException("wallet unavailable")).when(service).reverse("DR-1",BigDecimal.ONE);
        var scheduler=new DirectReferralRecoveryScheduler(mapper,service,policies);
        scheduler.recover();scheduler.recover();scheduler.recover();
        verify(service).reverse("DR-101",BigDecimal.ONE);
        verify(service,times(2)).reverse("DR-1",BigDecimal.ONE);
        verify(mapper,times(2)).pendingRecovery("SANDBOX","current-run",0);
        verify(mapper).pendingRecovery("SANDBOX","current-run",100);
        verify(mapper).pendingRecovery("SANDBOX","current-run",101);
        verifyNoMoreInteractions(mapper);
    }
}
