package ffdd.opsconsole.auth.application;

import ffdd.opsconsole.platform.facade.PlatformConfigFacade;
import ffdd.opsconsole.shared.exception.BizException;

/** K2/C6 write the same App-wide OTP authority consumed by registration, login and reset. */
record AppOtpPolicy(int ttlMinutes, int cooldownSeconds, int max24h, int maxVerifyAttempts) {
    static AppOtpPolicy load(PlatformConfigFacade configs) {
        return new AppOtpPolicy(read(configs, "auth.risk.otp_ttl_minutes", 5, 1, 15),
                read(configs, "auth.risk.otp_send_cooldown_seconds", 60, 30, 300),
                read(configs, "auth.risk.otp_send_day_limit", 10, 5, 50),
                read(configs, "auth.risk.otp_max_verify_attempts", 5, 1, 10));
    }

    private static int read(PlatformConfigFacade configs, String key, int fallback, int min, int max) {
        String raw = configs.activeValue(key).orElse(null);
        if (raw == null) return fallback;
        try {
            int value = Integer.parseInt(raw.trim());
            if (value >= min && value <= max) return value;
        } catch (NumberFormatException ignored) { }
        throw new BizException(503, "APP_OTP_POLICY_INVALID");
    }
}
