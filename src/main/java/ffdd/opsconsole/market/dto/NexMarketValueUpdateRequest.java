package ffdd.opsconsole.market.dto;

public record NexMarketValueUpdateRequest(
        String value,
        String reason,
        String operator,
        String decisionRef,
        String dispositionPlan,
        String triggerBasis,
        String expectedValue) {
    public NexMarketValueUpdateRequest(String value, String reason, String operator) {
        this(value, reason, operator, null, null, null, null);
    }

    public NexMarketValueUpdateRequest(
            String value,
            String reason,
            String operator,
            String expectedValue) {
        this(value, reason, operator, null, null, null, expectedValue);
    }

    public NexMarketValueUpdateRequest(
            String value,
            String reason,
            String operator,
            String decisionRef,
            String dispositionPlan,
            String triggerBasis) {
        this(value, reason, operator, decisionRef, dispositionPlan, triggerBasis, null);
    }
}
