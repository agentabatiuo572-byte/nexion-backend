package ffdd.opsconsole.device.application;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import ffdd.opsconsole.device.mapper.AppTaskAssignmentMapper;
import java.util.List;
import org.junit.jupiter.api.Test;

class TaskAssignmentDispatchSchedulerTest {
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
