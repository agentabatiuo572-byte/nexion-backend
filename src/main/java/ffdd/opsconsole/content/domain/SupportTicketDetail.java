package ffdd.opsconsole.content.domain;

import java.util.List;

public record SupportTicketDetail(
        SupportTicketView ticket,
        List<SupportTicketMessageView> messages,
        SupportTicketSlaTarget slaTarget,
        boolean historyTruncated,
        Long nextCursor) {
    public SupportTicketDetail(SupportTicketView ticket, List<SupportTicketMessageView> messages) {
        this(ticket, messages, null, false, null);
    }

    public SupportTicketDetail(
            SupportTicketView ticket, List<SupportTicketMessageView> messages, SupportTicketSlaTarget slaTarget) {
        this(ticket, messages, slaTarget, false, null);
    }

    public SupportTicketDetail(SupportTicketView ticket, List<SupportTicketMessageView> messages,
            SupportTicketSlaTarget slaTarget, boolean historyTruncated) {
        this(ticket, messages, slaTarget, historyTruncated, null);
    }
}
