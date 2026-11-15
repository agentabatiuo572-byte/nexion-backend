package ffdd.opsconsole.user.domain;

/**
 * Daily counter scope flags apply to {@code otpToday} and {@code captchaTriggeredToday}, not the rolling seven-day count.
 *
 * <p>{@code otpToday} counts {@code nx_user_otp_challenge} rows created since the business-day start. It includes
 * non-registration OTP flows and challenge rows left behind by failed delivery attempts; registration OTPs live in
 * {@code nx_user_registration_otp} and are not included.</p>
 *
 * <p>{@code captchaTriggeredToday} counts {@code nx_event_outbox} rows with event type
 * {@code auth.captcha_required}. No backend producer for that event was found in this repository, so zero is not
 * proof that no CAPTCHA challenge happened.</p>
 *
 * <p>{@code stuffingClusters7d} is not a credential-stuffing detector. It counts multi-account clusters first inserted
 * in the rolling seven days whose computed strength reached the K1 freeze-suggestion threshold. The query does not
 * filter disposition, so closed and released clusters remain counted. The source graph uses shared registration IP,
 * device fingerprint and payment-instrument evidence, not password-failure counts.</p>
 */
public record UserRegistrationRiskStats(
        long otpToday,
        long captchaTriggeredToday,
        long lockedShort,
        long lockedLong,
        long locked,
        long stuffingClusters7d,
        boolean captchaTemporarilyDisabled,
        String captchaRestoreAt,
        long captchaRemainingSeconds,
        boolean otpIncludesRegistration,
        boolean otpCountsSuccessfulSends,
        boolean captchaTriggeredProducerFound,
        boolean countsUseBusinessDay,
        boolean stuffingClusters7dIsCredentialStuffing,
        boolean stuffingClusters7dCountsMultiAccountThresholdHits,
        boolean stuffingClusters7dCountsFirstInsertedOnly,
        boolean stuffingClusters7dIncludesAllDispositions) {
}
