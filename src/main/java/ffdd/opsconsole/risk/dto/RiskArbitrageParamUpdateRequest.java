package ffdd.opsconsole.risk.dto;

public record RiskArbitrageParamUpdateRequest(
        String value,
        String reason,
        String operator,
        Long expectedVersion
) {
    public RiskArbitrageParamUpdateRequest(String value, String reason, String operator) {
        this(value, reason, operator, 0L);
    }
}
