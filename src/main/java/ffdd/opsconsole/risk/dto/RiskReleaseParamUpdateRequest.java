package ffdd.opsconsole.risk.dto;

public record RiskReleaseParamUpdateRequest(
        String value,
        Long expectedVersion,
        String reason,
        String operator
) {
}
