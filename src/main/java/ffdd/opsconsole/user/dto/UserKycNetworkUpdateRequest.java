package ffdd.opsconsole.user.dto;

public record UserKycNetworkUpdateRequest(
        String value,
        String reason,
        String operator) {
}
