package ffdd.opsconsole.content.domain;

import ffdd.opsconsole.content.dto.SupportTicketQueryRequest;
import ffdd.opsconsole.shared.api.PageResult;

/**
 * Read-only access to historical support tickets. Ticket workflows are retired (see RetiredSupportTickets);
 * the rows remain searchable from the admin global search under the caller's current support scope.
 */
public interface SupportTicketRepository {
    PageResult<SupportTicketView> pageTickets(SupportTicketQueryRequest request, SupportGroupFacts.ReadScope scope);
}
