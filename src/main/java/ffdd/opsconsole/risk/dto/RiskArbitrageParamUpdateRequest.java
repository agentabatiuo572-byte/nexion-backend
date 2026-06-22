package ffdd.opsconsole.risk.dto;

public record RiskArbitrageParamUpdateRequest(
        String value,
        String reason,
        String operator
) {
}
