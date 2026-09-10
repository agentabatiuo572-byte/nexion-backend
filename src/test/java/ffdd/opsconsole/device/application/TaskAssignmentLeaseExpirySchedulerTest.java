package ffdd.opsconsole.device.application;

import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import ffdd.opsconsole.device.mapper.AppTaskAssignmentMapper;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.Test;

class TaskAssignmentLeaseExpirySchedulerTest {
    @Test
    void advancesTaskCursorPastRejectedFrontPageSoLaterExpiredLeasesAreNotStarved() {
        var mapper = mock(AppTaskAssignmentMapper.class);
        var service = mock(AppTaskAssignmentService.class);
        LocalDateTime now = LocalDateTime.of(2026, 9, 10, 12, 0);
        when(mapper.leaseExpiryCandidates(0L, 2, now)).thenReturn(List.of(
                new AppTaskAssignmentMapper.LeaseExpiryCandidate(41L, 7L, 11L, "CTA-41"),
                new AppTaskAssignmentMapper.LeaseExpiryCandidate(42L, 8L, 12L, "CTA-42")));
        when(mapper.leaseExpiryCandidates(42L, 2, now)).thenReturn(List.of(
                new AppTaskAssignmentMapper.LeaseExpiryCandidate(101L, 9L, 21L, "CTA-101")));
        doThrow(new IllegalStateException("stale candidate"))
                .when(service).expirePendingLease(7L, 11L, "CTA-41");
        doThrow(new IllegalStateException("stale candidate"))
                .when(service).expirePendingLease(8L, 12L, "CTA-42");
        var scheduler = new TaskAssignmentLeaseExpiryScheduler(mapper, service, 2,
                Clock.fixed(now.toInstant(ZoneOffset.UTC), ZoneOffset.UTC));

        scheduler.expireLeases();
        scheduler.expireLeases();

        verify(service).expirePendingLease(9L, 21L, "CTA-101");
    }
}
