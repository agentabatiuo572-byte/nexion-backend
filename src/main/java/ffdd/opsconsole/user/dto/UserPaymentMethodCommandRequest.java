package ffdd.opsconsole.user.dto;

public record UserPaymentMethodCommandRequest(String reason, Long expectedVersion, String operator) {}
