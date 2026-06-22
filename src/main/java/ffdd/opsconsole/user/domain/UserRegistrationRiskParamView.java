package ffdd.opsconsole.user.domain;

public record UserRegistrationRiskParamView(
        String group,
        String key,
        String name,
        String sub,
        String value,
        String unit,
        int min,
        int max,
        boolean readOnly,
        String note,
        String configKey) {
}
