package ffdd.opsconsole.user.domain;

public record UserCredentialParamView(
        String key,
        String name,
        String value,
        String unit,
        int min,
        int max,
        boolean readOnly,
        String note,
        String configKey) {
}
