package ffdd.opsconsole.content.domain;

/**
 * M2-only least-privilege projection used to create or reassign a support ticket.
 * Eligibility flags stay server-side so M2 cannot infer the wider M1 seat profile.
 */
public record SupportTicketAssigneeCandidateView(
        Long adminId,
        String name) {
}
