package ffdd.opsconsole.risk.dto;

public record RiskArbitrageActionRequest(
        String reason,
        String operator,
        Long expectedVersion,
        Long clusterExpectedVersion
) {
    public RiskArbitrageActionRequest(String reason, String operator) {
        this(reason, operator, null, null);
    }
}
