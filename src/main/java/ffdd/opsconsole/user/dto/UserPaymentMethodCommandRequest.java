package ffdd.opsconsole.user.dto;

public record UserPaymentMethodCommandRequest(
        String reason, Long expectedVersion, String expectedValue, String operator) {
    public UserPaymentMethodCommandRequest(String reason, Long expectedVersion, String operator) {
        this(reason, expectedVersion, null, operator);
    }
}
