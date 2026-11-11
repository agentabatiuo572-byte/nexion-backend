package ffdd.opsconsole.growth.dto;

/**
 * Controlled retirement of a historical points wheel tier. It only deactivates the retired tier;
 * no points payout is restored and the active pool must later be completed to 100% with current kinds.
 */
public record GrowthWheelTierRetireRequest(
        String reason,
        String operator,
        Boolean confirmed,
        String expectedSignature) {
}
