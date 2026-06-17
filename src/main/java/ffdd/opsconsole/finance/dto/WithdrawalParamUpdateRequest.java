package ffdd.opsconsole.finance.dto;

public record WithdrawalParamUpdateRequest(
        String key,
        String value,
        String reason,
        String operator) {
}
