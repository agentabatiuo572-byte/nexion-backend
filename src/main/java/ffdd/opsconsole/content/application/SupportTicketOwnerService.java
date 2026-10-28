package ffdd.opsconsole.content.application;

import ffdd.opsconsole.common.boundary.ApplicationService;
import ffdd.opsconsole.content.domain.DedicatedAdvisorBindingView;
import ffdd.opsconsole.content.mapper.SupportBindingMapper;
import ffdd.opsconsole.content.mapper.SupportTicketMapper;
import java.time.Clock;
import java.time.LocalDateTime;
import lombok.RequiredArgsConstructor;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Historical ticket responsibility is a projection of the customer's formal ACTIVE advisor binding.
 * Ticket workflows are retired, but binding changes keep the retained rows' owner columns in sync.
 */
@ApplicationService
@RequiredArgsConstructor
public class SupportTicketOwnerService {
    private final SupportOwnershipService ownership;
    private final SupportBindingMapper bindings;
    private final SupportTicketMapper tickets;
    private final Clock clock;

    /** Called inside the binding transaction, before maintenance events and audit are published. */
    @Transactional(propagation = Propagation.MANDATORY)
    public void synchronizeCurrentOwner(Long customerId) {
        ownership.lockCustomer(customerId);
        var owner = currentOwner(customerId);
        tickets.synchronizeOwner(customerId, owner.adminId(), owner.name(), LocalDateTime.now(clock));
    }

    private DedicatedAdvisorBindingView currentOwner(Long customerId) {
        // A busy/disabled advisor retains responsibility until the formal supervisor transfer.
        var owner = tickets.currentOwner(customerId);
        if (owner != null) return owner;
        if (bindings.poolVersion(customerId) == null) bindings.enterPool(customerId, "MIGRATION_REVIEW");
        return new DedicatedAdvisorBindingView(null, "Unassigned");
    }
}
