package ffdd.opsconsole.user.application;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.Map;
import java.util.Optional;

/** Canonical whitelist and absolute-deadline parser for the temporary CAPTCHA bypass. */
public final class RegistrationRiskCaptchaWindow {
    public static final String CONFIG_KEY = "auth.risk.captcha_off_window";
    public static final String VERSION_KEY = "auth.risk.c6.version";
    public static final Map<String, Duration> ALLOWED_WINDOWS = Map.of(
            "30 分钟后自动恢复", Duration.ofMinutes(30),
            "1 小时后自动恢复", Duration.ofHours(1),
            "2 小时后自动恢复", Duration.ofHours(2),
            "4 小时后自动恢复", Duration.ofHours(4));

    private RegistrationRiskCaptchaWindow() {
    }

    public static Optional<Instant> restoreAt(String rawValue) {
        if (rawValue == null || rawValue.isBlank()) return Optional.empty();
        try {
            return Optional.of(Instant.parse(rawValue.trim()));
        } catch (DateTimeParseException ignored) {
            return Optional.empty();
        }
    }

    public static State state(String rawValue, Clock clock) {
        Optional<Instant> parsed = restoreAt(rawValue);
        if (parsed.isEmpty()) return new State(false, null, 0L, rawValue != null && !rawValue.isBlank());
        Instant deadline = parsed.get();
        long remaining = Math.max(0L, Duration.between(clock.instant(), deadline).getSeconds());
        return new State(remaining > 0L, deadline, remaining, false);
    }

    public static Instant deadlineForSelection(String selection, Clock clock) {
        Duration duration = ALLOWED_WINDOWS.get(selection == null ? "" : selection.trim());
        if (duration == null) throw new IllegalArgumentException("CAPTCHA_RESTORE_WINDOW_REJECTED");
        return clock.instant().plus(duration);
    }

    public record State(boolean disabled, Instant restoreAt, long remainingSeconds, boolean malformed) {
        public boolean requiresPersistentRestore() {
            return malformed || (restoreAt != null && !disabled);
        }
    }
}
