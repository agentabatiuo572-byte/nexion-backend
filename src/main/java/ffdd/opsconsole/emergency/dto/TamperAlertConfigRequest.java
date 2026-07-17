package ffdd.opsconsole.emergency.dto;

public record TamperAlertConfigRequest(
        Integer threshold,
        Boolean feedK4,
        Integer expectedThreshold,
        Boolean expectedFeedK4,
        String reason,
        String operator) {
    public TamperAlertConfigRequest(Integer threshold, Boolean feedK4, String reason, String operator) {
        this(threshold, feedK4, null, null, reason, operator);
    }
}
