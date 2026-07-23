package ffdd.opsconsole.market.dto;

public record NexMarketValueUpdateRequest(
        String value,
        String reason,
        String operator,
        String decisionRef,
        String dispositionPlan,
        String triggerBasis) {
    public NexMarketValueUpdateRequest(String value, String reason, String operator) {
        this(value, reason, operator, null, null, null);
    }
}
