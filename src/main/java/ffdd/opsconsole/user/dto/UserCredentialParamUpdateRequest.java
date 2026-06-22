package ffdd.opsconsole.user.dto;

public record UserCredentialParamUpdateRequest(
        String value,
        String reason,
        String operator) {
}
