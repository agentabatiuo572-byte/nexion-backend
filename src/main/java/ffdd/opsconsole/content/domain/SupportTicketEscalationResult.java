package ffdd.opsconsole.content.domain;

public record SupportTicketEscalationResult(
        SupportTicketDetail ticket,
        ContentConversationView conversation) {
}
