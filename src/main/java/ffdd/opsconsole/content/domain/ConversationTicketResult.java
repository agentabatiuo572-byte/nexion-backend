package ffdd.opsconsole.content.domain;

public record ConversationTicketResult(
        ContentConversationView conversation,
        SupportTicketDetail ticket) {
}
