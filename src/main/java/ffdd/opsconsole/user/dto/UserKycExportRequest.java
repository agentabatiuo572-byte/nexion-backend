package ffdd.opsconsole.user.dto;

public record UserKycExportRequest(
        String scope,
        String reason,
        String operator) {
}
