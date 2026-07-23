package ffdd.opsconsole.user.domain;

public record UserRegistrationRiskStats(
        long otpToday,
        long captchaTriggeredToday,
        long lockedShort,
        long lockedLong,
        long locked,
        long stuffingClusters7d,
        boolean captchaTemporarilyDisabled,
        String captchaRestoreAt,
        long captchaRemainingSeconds) {
}
