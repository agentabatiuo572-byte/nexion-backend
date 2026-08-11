package ffdd.opsconsole.growth.dto;

/**
 * Acceptance-only, explicitly scoped H8 sandbox execution request.  This DTO is
 * deliberately separate from the production A2 settlement request: the route
 * that accepts it is not registered outside the acceptance profile.
 */
public record AcceptanceSandboxReferralSettlementRequest(
        Long invitedUserId,
        String reason,
        String operator) {
}
