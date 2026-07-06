package ffdd.opsconsole.platform.dto;

public record AdminAccountPasswordResetResponse(
        AdminAccountOverview.OperatorRecord account,
        String temporaryPassword) {
}
