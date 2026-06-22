package ffdd.opsconsole.risk.domain;

public record RiskArbitrageStatView(
        String key,
        String label,
        String value,
        String sub,
        String tone
) {
}
