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
        int secondaryMin,
        int secondaryMax,
        String secondaryUnit,
        long version,
        boolean readOnly,
        String note,
        String configKey) {
}
