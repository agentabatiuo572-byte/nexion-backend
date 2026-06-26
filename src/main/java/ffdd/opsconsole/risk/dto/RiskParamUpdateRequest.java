package ffdd.opsconsole.risk.dto;

public record RiskParamUpdateRequest(
        String value,
        String reason,
        String operator
) {
}
