package ffdd.opsconsole.user.dto;

public record UserCredentialParamUpdateRequest(
        String value,
        String reason,
        String operator,
        Long expectedVersion) {
    public UserCredentialParamUpdateRequest(String value, String reason, String operator) {
        this(value, reason, operator, 0L);
    }
}
