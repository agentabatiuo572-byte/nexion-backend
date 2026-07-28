package ffdd.opsconsole.content.domain;

import java.util.List;

public record SupportTicketDetail(
        SupportTicketView ticket,
        List<SupportTicketMessageView> messages,
        SupportTicketSlaTarget slaTarget) {
    public SupportTicketDetail(SupportTicketView ticket, List<SupportTicketMessageView> messages) {
        this(ticket, messages, null);
    }
}
