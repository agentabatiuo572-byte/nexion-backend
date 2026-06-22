package ffdd.opsconsole.user.dto;

public record UserRegistrationRiskParamUpdateRequest(
        String value,
        String reason,
        String operator) {
}
