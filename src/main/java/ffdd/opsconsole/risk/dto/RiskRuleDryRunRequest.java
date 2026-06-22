package ffdd.opsconsole.risk.dto;

public record RiskRuleDryRunRequest(
        String reason,
        String operator
) {
}
