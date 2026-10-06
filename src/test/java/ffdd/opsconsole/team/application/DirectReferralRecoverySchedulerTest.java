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
        doThrow(new IllegalStateException("wallet unavailable")).when(service).retryRecovery("DR-1");
        var scheduler=new DirectReferralRecoveryScheduler(mapper,service,policies);
        scheduler.recover();scheduler.recover();scheduler.recover();
        verify(service).retryRecovery("DR-101");
        verify(service,times(2)).retryRecovery("DR-1");
        verify(mapper,times(3)).waitingCalculation("SANDBOX","current-run","");
        verify(mapper,times(3)).reissueRecoveryOrders("SANDBOX","current-run","");
        verify(mapper,times(2)).pendingRecovery("SANDBOX","current-run",0);
        verify(mapper).pendingRecovery("SANDBOX","current-run",100);
        verify(mapper).pendingRecovery("SANDBOX","current-run",101);
        verifyNoMoreInteractions(mapper);
    }
    @Test void failedCalculationAndReissueFirstPagesRotateIndependentlyAndWrapWithoutLosingScope() {
        var mapper=mock(DirectReferralMapper.class);var service=mock(DirectReferralService.class);var policies=mock(DirectReferralPolicyService.class);
        when(policies.scope()).thenReturn(new DirectReferralPolicyService.Scope("SANDBOX","current-run",1));
        var first=LongStream.rangeClosed(1,100).mapToObj(id->String.format("%03d",id)).toList();
        when(mapper.waitingCalculation("SANDBOX","current-run","")).thenReturn(first);
        when(mapper.waitingCalculation("SANDBOX","current-run","100")).thenReturn(List.of("101"));
        when(mapper.reissueRecoveryOrders("SANDBOX","current-run","")).thenReturn(first);
        when(mapper.reissueRecoveryOrders("SANDBOX","current-run","100")).thenReturn(List.of("101"));
        for(String key:first){doThrow(new IllegalStateException("source still unavailable")).when(service).resumeGroup(key);doThrow(new IllegalStateException("wallet still unavailable")).when(service).recoverOrderReissues(key);}
        var scheduler=new DirectReferralRecoveryScheduler(mapper,service,policies);scheduler.recover();scheduler.recover();scheduler.recover();
        verify(service).resumeGroup("101");verify(service).recoverOrderReissues("101");
        for(String key:first){verify(service,times(2)).resumeGroup(key);verify(service,times(2)).recoverOrderReissues(key);}
        verify(mapper,times(2)).waitingCalculation("SANDBOX","current-run","");verify(mapper).waitingCalculation("SANDBOX","current-run","100");verify(mapper).waitingCalculation("SANDBOX","current-run","101");
        verify(mapper,times(2)).reissueRecoveryOrders("SANDBOX","current-run","");verify(mapper).reissueRecoveryOrders("SANDBOX","current-run","100");verify(mapper).reissueRecoveryOrders("SANDBOX","current-run","101");
    }
}
