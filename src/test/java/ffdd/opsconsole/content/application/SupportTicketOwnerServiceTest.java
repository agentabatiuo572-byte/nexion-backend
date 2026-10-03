package ffdd.opsconsole.content.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

import ffdd.opsconsole.content.domain.DedicatedAdvisorBindingView;
import ffdd.opsconsole.content.mapper.SupportBindingMapper;
import ffdd.opsconsole.content.mapper.SupportTicketMapper;
import ffdd.opsconsole.shared.exception.BizException;
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
    void omittedOrMatchingOwnerUsesTheFormalBindingAndServerName() {
        var owner = new DedicatedAdvisorBindingView(7L, "Current advisor");
        when(tickets.currentOwner(1L)).thenReturn(owner);
        assertThat(service.resolveForCreate(1L, null)).isEqualTo(owner);
        assertThat(service.resolveForCreate(1L, 7L)).isEqualTo(owner);
        var locks = inOrder(ownership, tickets);
        locks.verify(ownership).lockCustomer(1L);
        locks.verify(tickets).currentOwner(1L);
        verifyNoInteractions(bindings);
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(longs = {8L, 0L, -1L})
    void explicitOtherOwnerIsRejectedInsteadOfChangingTheBinding(long requestedOwner) {
        when(tickets.currentOwner(1L)).thenReturn(new DedicatedAdvisorBindingView(7L, "Current advisor"));
        assertThatThrownBy(() -> service.resolveForCreate(1L, requestedOwner))
                .isInstanceOf(BizException.class).hasMessage("SUPPORT_TICKET_OWNER_BINDING_MISMATCH")
                .extracting("code").isEqualTo(409);
        verifyNoInteractions(bindings);
        verify(tickets, never()).synchronizeOwner(any(), any(), any(), any());
    }

    @Test
    void unboundCustomerKeepsTheExistingPoolReason() {
        when(bindings.poolVersion(1L)).thenReturn(4L);
        assertThat(service.resolveForCreate(1L, null)).isEqualTo(new DedicatedAdvisorBindingView(null, "Unassigned"));
        verify(bindings, never()).enterPool(any(), any());
    }

    @Test
    void legacyUnboundCustomerEntersExistingPoolWithoutInventingAnAgent() {
        when(bindings.poolVersion(1L)).thenReturn(null);
        assertThat(service.resolveForCreate(1L, null).adminId()).isNull();
        verify(bindings).enterPool(1L, "MIGRATION_REVIEW");
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
