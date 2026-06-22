package ffdd.opsconsole.user.domain;

public record UserRegistrationRiskK1GuardView(
        String name,
        String k1Key,
        String rejectCode,
        String suggestedPath) {
}
