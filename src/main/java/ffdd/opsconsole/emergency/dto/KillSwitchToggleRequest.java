package ffdd.opsconsole.emergency.dto;

public record KillSwitchToggleRequest(
        String enabled,
        String reason,
        String operator) {
}
