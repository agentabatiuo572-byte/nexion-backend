package ffdd.opsconsole.user.facade;

public record UserSecurityRiskDecision(
        String scenario,
        Long userId,
        String subjectKey,
        boolean blocked,
        String action,
        String reason,
        int observedCount,
        int primaryThreshold,
        int secondaryThreshold,
        int duration,
        String durationUnit,
        boolean captchaRequired,
        boolean passwordResetRequired,
        boolean twoFactorEnabled) {
}
