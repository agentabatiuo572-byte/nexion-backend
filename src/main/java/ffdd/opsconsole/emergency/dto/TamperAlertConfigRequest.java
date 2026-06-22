package ffdd.opsconsole.emergency.dto;

public record TamperAlertConfigRequest(Integer threshold, Boolean feedK4, String reason, String operator) {
}
