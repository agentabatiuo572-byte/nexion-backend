package ffdd.opsconsole.emergency.dto;

public record KillSwitchToggleRequest(
        String enabled,
        String reason,
        String operator,
        String triggerBasis,
        String dispositionPlan) {
    public KillSwitchToggleRequest(String enabled, String reason, String operator) {
        this(enabled, reason, operator, null, null);
    }
}
