package ffdd.opsconsole.device.application;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.never;
import static org.mockito.ArgumentMatchers.*;

import ffdd.opsconsole.device.mapper.AppTaskAssignmentMapper;
import java.util.List;
import org.junit.jupiter.api.Test;

class TaskAssignmentDispatchSchedulerTest {
    @Test
    void sixThousandV2SlotsAreExcludedBeforeTheLegacyCandidatePage() {
        var mapper=mock(AppTaskAssignmentMapper.class);var service=mock(AppTaskAssignmentService.class);
        var enrolled=java.util.stream.LongStream.rangeClosed(1,6000).boxed().toList();
        var environment=new org.springframework.mock.env.MockEnvironment().withProperty("nexion.compute-v2.enabled","true")
                .withProperty("nexion.compute-v2.device-ids",enrolled.stream().map(Object::toString).collect(java.util.stream.Collectors.joining(",")));
        when(mapper.assignmentCandidatesExcluding(0L,100,enrolled)).thenReturn(List.of(new AppTaskAssignmentMapper.AssignmentCandidate(9L,6001L)));
        new TaskAssignmentDispatchScheduler(mapper,service,100,environment).dispatch();
        verify(service).assignAutomatically(9L,6001L);verify(mapper,never()).assignmentCandidates(anyLong(),anyInt());
    }
    @Test
    void dispatchesEveryEligibleProductionDeviceThroughIndependentServiceTransactions() {
        var mapper = mock(AppTaskAssignmentMapper.class);
        var service = mock(AppTaskAssignmentService.class);
        when(mapper.assignmentCandidates(0L, 100)).thenReturn(List.of(
                new AppTaskAssignmentMapper.AssignmentCandidate(7L, 11L),
                new AppTaskAssignmentMapper.AssignmentCandidate(8L, 12L)));
        var scheduler = new TaskAssignmentDispatchScheduler(mapper, service, 100);

        scheduler.dispatch();

        verify(service).assignAutomatically(7L, 11L);
        verify(service).assignAutomatically(8L, 12L);
    }

    @Test
    void oneCandidateFailureDoesNotPreventLaterCandidatesFromBeingDispatched() {
        var mapper = mock(AppTaskAssignmentMapper.class);
        var service = mock(AppTaskAssignmentService.class);
        when(mapper.assignmentCandidates(0L, 100)).thenReturn(List.of(
                new AppTaskAssignmentMapper.AssignmentCandidate(7L, 11L),
                new AppTaskAssignmentMapper.AssignmentCandidate(8L, 12L)));
        doThrow(new IllegalStateException("transient failure"))
                .when(service).assignAutomatically(7L, 11L);
        var scheduler = new TaskAssignmentDispatchScheduler(mapper, service, 100);

        scheduler.dispatch();

        verify(service).assignAutomatically(8L, 12L);
    }

    @Test
    void advancesTheCandidateCursorSoARejectedFrontBatchCannotStarveLaterDevices() {
        var mapper = mock(AppTaskAssignmentMapper.class);
        var service = mock(AppTaskAssignmentService.class);
        when(mapper.assignmentCandidates(0L, 2)).thenReturn(List.of(
                new AppTaskAssignmentMapper.AssignmentCandidate(7L, 11L),
                new AppTaskAssignmentMapper.AssignmentCandidate(8L, 12L)));
        when(mapper.assignmentCandidates(12L, 2)).thenReturn(List.of(
                new AppTaskAssignmentMapper.AssignmentCandidate(9L, 101L)));
        doThrow(new IllegalStateException("not eligible"))
                .when(service).assignAutomatically(7L, 11L);
        doThrow(new IllegalStateException("not eligible"))
                .when(service).assignAutomatically(8L, 12L);
        var scheduler = new TaskAssignmentDispatchScheduler(mapper, service, 2);

        scheduler.dispatch();
        scheduler.dispatch();

        verify(service).assignAutomatically(9L, 101L);
    }
}
