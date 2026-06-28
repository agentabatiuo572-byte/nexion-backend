package ffdd.opsconsole.user.dto;

public record UserProfileExportRequest(
        String keyword,
        String status,
        String kycStatus,
        Integer riskMin,
        String reason,
        String operator) {
}
