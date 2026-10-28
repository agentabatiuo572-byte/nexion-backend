package ffdd.opsconsole.content.application;

import static org.mockito.Mockito.*;

import ffdd.opsconsole.content.domain.DedicatedAdvisorBindingView;
import ffdd.opsconsole.content.mapper.SupportBindingMapper;
import ffdd.opsconsole.content.mapper.SupportTicketMapper;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;

class SupportTicketOwnerServiceTest {
    private final SupportOwnershipService ownership = mock(SupportOwnershipService.class);
    private final SupportBindingMapper bindings = mock(SupportBindingMapper.class);
    private final SupportTicketMapper tickets = mock(SupportTicketMapper.class);
    private final Clock clock = Clock.fixed(Instant.parse("2026-10-03T00:00:00Z"), ZoneOffset.UTC);
    private final SupportTicketOwnerService service = new SupportTicketOwnerService(ownership, bindings, tickets, clock);

    @Test
    void unboundCustomerKeepsTheExistingPoolReason() {
        when(bindings.poolVersion(1L)).thenReturn(4L);
        service.synchronizeCurrentOwner(1L);
        verify(tickets).synchronizeOwner(1L, null, "Unassigned", LocalDateTime.now(clock));
        verify(bindings, never()).enterPool(any(), any());
    }

    @Test
    void legacyUnboundCustomerEntersExistingPoolWithoutInventingAnAgent() {
        when(bindings.poolVersion(1L)).thenReturn(null);
        service.synchronizeCurrentOwner(1L);
        verify(bindings).enterPool(1L, "MIGRATION_REVIEW");
        verify(tickets).synchronizeOwner(1L, null, "Unassigned", LocalDateTime.now(clock));
    }

    @Test
    void bindingChangeSynchronizesItsCurrentOwnerInsideTheCustomerLock() {
        when(tickets.currentOwner(1L)).thenReturn(new DedicatedAdvisorBindingView(8L, "New advisor"));
        service.synchronizeCurrentOwner(1L);
        var locks = inOrder(ownership, tickets);
        locks.verify(ownership).lockCustomer(1L);
        locks.verify(tickets).currentOwner(1L);
        locks.verify(tickets).synchronizeOwner(1L, 8L, "New advisor", LocalDateTime.now(clock));
    }
}
