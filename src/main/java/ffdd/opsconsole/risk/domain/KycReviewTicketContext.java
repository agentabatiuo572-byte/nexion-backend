package ffdd.opsconsole.risk.domain;

public record KycReviewTicketContext(
        String ticketId,
        String ticketType,
        String userNo,
        String status,
        String infoJson,
        long version) {
}
