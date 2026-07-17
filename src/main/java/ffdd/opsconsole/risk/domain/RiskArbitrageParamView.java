package ffdd.opsconsole.risk.domain;

public record RiskArbitrageParamView(
        String key,
        String name,
        String value,
        String sub,
        String note,
        long version
) {
    public RiskArbitrageParamView(String key, String name, String value, String sub, String note) {
        this(key, name, value, sub, note, 0L);
    }
}
