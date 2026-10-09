package ffdd.opsconsole.promotion.application;

import java.util.List;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class PromotionRewardSchedulerTest {
    private final PromotionRewardService rewards=mock(PromotionRewardService.class);
    private final PromotionAvailabilityService availability=mock(PromotionAvailabilityService.class);
    private final PromotionRewardScheduler scheduler=new PromotionRewardScheduler(rewards,availability);

    @Test void oneDispatchReversesAtMostOneBatchOfFifty(){
        var batch=ids("r",50);
        when(rewards.reversalsAfter(anyString(),eq(50))).thenReturn(batch,List.of("tail"),List.of());

        scheduler.dispatch();

        verify(rewards).reversalsAfter("",50);
        verify(rewards,times(1)).reversalsAfter(anyString(),eq(50));
        for(String id:batch)verify(rewards).reverseRefund(eq(id),anyString());
        verify(rewards,times(50)).reverseRefund(anyString(),anyString());
        verify(rewards,never()).reverseRefund(eq("tail"),anyString());
    }

    @Test void fiftyFailedReversalsDoNotStarveHealthyNextBatch(){
        var blocked=ids("blocked",50);
        when(rewards.reversalsAfter("",50)).thenReturn(blocked);
        when(rewards.reversalsAfter("blocked050",50)).thenReturn(List.of("healthy"));
        when(rewards.reversalsAfter("healthy",50)).thenReturn(List.of());
        doThrow(new IllegalStateException("UNKNOWN_RECOVERY"))
            .when(rewards).reverseRefund(startsWith("blocked"),anyString());

        scheduler.dispatch();
        verify(rewards,never()).reverseRefund(eq("healthy"),anyString());
        scheduler.dispatch();

        verify(rewards).reversalsAfter("blocked050",50);
        verify(rewards).reverseRefund(eq("healthy"),anyString());
        verify(rewards,times(50)).recordActionFailure(anyString(),anyString(),eq("REVERSE"),eq("UNKNOWN_RECOVERY"));
        for(String id:blocked)verify(rewards).reverseRefund(eq(id),anyString());
    }

    @Test void failedLastReversalAdvancesEvenIfItsFailureAuditAlsoFails(){
        when(rewards.reversalsAfter("",50)).thenReturn(List.of("first","last"));
        when(rewards.reversalsAfter("last",50)).thenReturn(List.of());
        doThrow(new IllegalStateException("RECOVERY_PENDING"))
            .when(rewards).reverseRefund(eq("last"),anyString());
        doThrow(new IllegalStateException("AUDIT_UNAVAILABLE"))
            .when(rewards).recordActionFailure(eq("last"),anyString(),eq("REVERSE"),eq("RECOVERY_PENDING"));

        assertDoesNotThrow(scheduler::dispatch);
        assertDoesNotThrow(scheduler::dispatch);

        var cursor=ArgumentCaptor.forClass(String.class);
        verify(rewards,times(2)).reversalsAfter(cursor.capture(),eq(50));
        assertEquals(List.of("","last"),cursor.getAllValues());
        var command=ArgumentCaptor.forClass(String.class);
        verify(rewards).reverseRefund(eq("last"),command.capture());
        verify(rewards).recordActionFailure("last",command.getValue(),"REVERSE","RECOVERY_PENDING");
    }

    @Test void emptyTailResetsCursorAndWrapsOnlyOnTheFollowingDispatch(){
        when(rewards.reversalsAfter("",50)).thenReturn(List.of("middle"),List.of("earlier"),List.of());
        when(rewards.reversalsAfter("middle",50)).thenReturn(List.of());
        when(rewards.reversalsAfter("earlier",50)).thenReturn(List.of());

        scheduler.dispatch();
        scheduler.dispatch();
        verify(rewards,never()).reverseRefund(eq("earlier"),anyString());
        scheduler.dispatch();

        var cursor=ArgumentCaptor.forClass(String.class);
        verify(rewards,times(3)).reversalsAfter(cursor.capture(),eq(50));
        assertEquals(List.of("","middle",""),cursor.getAllValues());
        verify(rewards).reverseRefund(eq("middle"),anyString());
        verify(rewards).reverseRefund(eq("earlier"),anyString());
    }

    @Test void emptyQueueStillGetsCheckedOnTheNextDispatch(){
        scheduler.dispatch();
        scheduler.dispatch();

        verify(rewards,times(2)).reversalsAfter("",50);
        verify(rewards,never()).reverseRefund(anyString(),anyString());
        verify(rewards,never()).issue(anyString(),anyString());
    }

    @Test void newSchedulerInstanceStartsAtTheBeginning(){
        when(rewards.reversalsAfter("",50)).thenReturn(List.of("middle"),List.of("earlier"),List.of());

        scheduler.dispatch();
        new PromotionRewardScheduler(rewards,availability).dispatch();

        verify(rewards,times(2)).reversalsAfter("",50);
        verify(rewards).reverseRefund(eq("middle"),anyString());
        verify(rewards).reverseRefund(eq("earlier"),anyString());
    }

    @Test void pendingIssuanceRunsBeforeRefundReversal(){
        when(rewards.pending(50)).thenReturn(List.of("fresh"));
        when(rewards.reversalsAfter("",50)).thenReturn(List.of("refund"));

        scheduler.dispatch();

        var order=inOrder(rewards);
        order.verify(rewards).pending(50);
        order.verify(rewards).issue(eq("fresh"),anyString());
        order.verify(rewards).reversalsAfter("",50);
        order.verify(rewards).reverseRefund(eq("refund"),anyString());
    }

    @Test void issuanceFailureRecordsTheOriginalCommandAndContinuesAfterAuditFailure(){
        when(rewards.pending(50)).thenReturn(List.of("failed","fresh"));
        doThrow(new IllegalStateException("ISSUE_FAILED"))
            .when(rewards).issue(eq("failed"),anyString());
        doThrow(new IllegalStateException("AUDIT_UNAVAILABLE"))
            .when(rewards).recordFailure(eq("failed"),anyString(),eq("ISSUE_FAILED"));

        assertDoesNotThrow(scheduler::dispatch);

        var command=ArgumentCaptor.forClass(String.class);
        verify(rewards).issue(eq("failed"),command.capture());
        verify(rewards).recordFailure("failed",command.getValue(),"ISSUE_FAILED");
        verify(rewards).issue(eq("fresh"),anyString());
        verify(rewards).reversalsAfter("",50);
    }

    @Test void capacityScanChecksAllHundredAndOneActivitiesBeforeIssuanceDespiteFailure(){
        var first=ids("a",100);
        when(availability.candidatesAfter("",100)).thenReturn(first);
        when(availability.candidatesAfter("a100",100)).thenReturn(List.of("a101"));
        doThrow(new IllegalStateException("CAPACITY_UNAVAILABLE"))
            .when(availability).pauseIfShort("a001");

        assertDoesNotThrow(scheduler::dispatch);
        assertDoesNotThrow(scheduler::dispatch);

        var order=inOrder(availability,rewards);
        for(int turn=0;turn<2;turn++){
            order.verify(availability).candidatesAfter("",100);
            for(String id:first)order.verify(availability).pauseIfShort(id);
            order.verify(availability).candidatesAfter("a100",100);
            order.verify(availability).pauseIfShort("a101");
            order.verify(rewards).pending(50);
            order.verify(rewards).reversalsAfter("",50);
        }
        verify(availability,times(202)).pauseIfShort(anyString());
        verify(availability,times(4)).candidatesAfter(anyString(),eq(100));
    }

    private static List<String> ids(String prefix,int count){
        return IntStream.rangeClosed(1,count).mapToObj(i->prefix+String.format("%03d",i)).toList();
    }
}
