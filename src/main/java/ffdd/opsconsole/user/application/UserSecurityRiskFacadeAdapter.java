package ffdd.opsconsole.user.application;

import ffdd.opsconsole.platform.facade.PlatformConfigFacade;
import ffdd.opsconsole.user.domain.UserOpsRepository;
import ffdd.opsconsole.user.domain.UserSecurityStatusView;
import ffdd.opsconsole.user.facade.UserSecurityRiskDecision;
import ffdd.opsconsole.user.facade.UserSecurityRiskFacade;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

@Service
@RequiredArgsConstructor
public class UserSecurityRiskFacadeAdapter implements UserSecurityRiskFacade {
    private static final String CAPTCHA_OFF_WINDOW_KEY = "auth.risk.captcha_off_window";

    private final UserOpsRepository userRepository;
    private final PlatformConfigFacade configFacade;

    @Override
    public UserSecurityRiskDecision evaluateLogin(Long userId) {
        int shortThreshold = Math.max(configInt("auth.risk.login_lock_threshold", 5), 1);
        int longThreshold = Math.max(configInt("auth.risk.login_long_lock_threshold", 10), shortThreshold);
        int shortLockMinutes = Math.max(configInt("auth.risk.lock_duration_minutes", 30), 1);
        int longLockHours = Math.max(configInt("auth.risk.long_lock_duration_hours", 24), 1);
        if (userId == null || userId <= 0) {
            return decision("LOGIN", userId, null, true, "USER_ID_REQUIRED", "USER_ID_REQUIRED",
                    0, shortThreshold, longThreshold, 0, "NONE", false, false, false);
        }
        return userRepository.securityStatus(userId)
                .map(status -> loginDecision(status, shortThreshold, longThreshold, shortLockMinutes, longLockHours))
                .orElseGet(() -> decision("LOGIN", userId, null, false, "ALLOW", "NO_SECURITY_STATE",
                        0, shortThreshold, longThreshold, 0, "NONE", false, false, false));
    }

    @Override
    public UserSecurityRiskDecision evaluateRegistrationOtp(
            String subjectKey,
            int otpSentLast24h,
            int secondsSinceLastOtp,
            boolean captchaPassed) {
        int cooldownSeconds = Math.max(configInt("auth.risk.otp_cooldown_seconds", 60), 1);
        int max24h = Math.max(configInt("auth.risk.otp_max_24h", 3), 1);
        int observedCount = Math.max(otpSentLast24h, 0);
        int lastOtpAge = Math.max(secondsSinceLastOtp, -1);
        if (lastOtpAge >= 0 && lastOtpAge < cooldownSeconds) {
            return decision("REGISTRATION_OTP", null, normalizeSubject(subjectKey), true,
                    "OTP_COOLDOWN", "OTP_COOLDOWN_ACTIVE", observedCount, cooldownSeconds,
                    max24h, cooldownSeconds - lastOtpAge, "SECONDS", false, false, false);
        }
        boolean captchaDisabled = configFacade.activeValue(CAPTCHA_OFF_WINDOW_KEY)
                .filter(StringUtils::hasText)
                .isPresent();
        if (observedCount >= max24h && !captchaPassed && !captchaDisabled) {
            return decision("REGISTRATION_OTP", null, normalizeSubject(subjectKey), true,
                    "CAPTCHA_REQUIRED", "OTP_24H_LIMIT_REACHED", observedCount, max24h,
                    max24h, 0, "NONE", true, false, false);
        }
        return decision("REGISTRATION_OTP", null, normalizeSubject(subjectKey), false,
                captchaPassed ? "ALLOW_CAPTCHA_PASSED" : "ALLOW", "REGISTRATION_RISK_ALLOWED",
                observedCount, cooldownSeconds, max24h, 0, "NONE", false, false, false);
    }

    private UserSecurityRiskDecision loginDecision(
            UserSecurityStatusView status,
            int shortThreshold,
            int longThreshold,
            int shortLockMinutes,
            int longLockHours) {
        int failCount = Math.max(status.loginFailCount(), 0);
        if (status.passwordResetRequired() || failCount >= longThreshold) {
            return decision("LOGIN", status.userId(), null, true, "LONG_LOCK",
                    status.passwordResetRequired() ? "PASSWORD_RESET_REQUIRED" : "LOGIN_LONG_LOCK_THRESHOLD_REACHED",
                    failCount, shortThreshold, longThreshold, longLockHours, "HOURS",
                    false, status.passwordResetRequired(), status.twoFactorEnabled());
        }
        if (status.locked() || failCount >= shortThreshold) {
            return decision("LOGIN", status.userId(), null, true, "SHORT_LOCK",
                    "LOGIN_LOCK_THRESHOLD_REACHED", failCount, shortThreshold, longThreshold,
                    shortLockMinutes, "MINUTES", false, false, status.twoFactorEnabled());
        }
        return decision("LOGIN", status.userId(), null, false, "ALLOW", "LOGIN_RISK_ALLOWED",
                failCount, shortThreshold, longThreshold, 0, "NONE", false, false, status.twoFactorEnabled());
    }

    private UserSecurityRiskDecision decision(
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
        return new UserSecurityRiskDecision(
                scenario,
                userId,
                subjectKey,
                blocked,
                action,
                reason,
                observedCount,
                primaryThreshold,
                secondaryThreshold,
                duration,
                durationUnit,
                captchaRequired,
                passwordResetRequired,
                twoFactorEnabled);
    }

    private int configInt(String key, int fallback) {
        return configFacade.activeValue(key)
                .map(String::trim)
                .filter(StringUtils::hasText)
                .map(value -> parseInt(value, fallback))
                .orElse(fallback);
    }

    private int parseInt(String value, int fallback) {
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException ex) {
            return fallback;
        }
    }

    private String normalizeSubject(String subjectKey) {
        return StringUtils.hasText(subjectKey) ? subjectKey.trim() : "";
    }
}
