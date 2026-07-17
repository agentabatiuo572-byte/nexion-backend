package ffdd.opsconsole.emergency.dto;

public record AutoTriggerConfirmationRequest(
        String incidentId,
        String decision,
        String reason,
        String operator) {
}
