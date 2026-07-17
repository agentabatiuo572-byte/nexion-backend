package ffdd.opsconsole.emergency.domain;

import java.util.Locale;
import java.util.Optional;

/**
 * Canonical parser for J1 kill-switch state.
 * A missing row means the business capability is enabled; every J1 reader and
 * every downstream enforcement point must use this same fail-compatible default.
 */
public final class KillSwitchState {
    private KillSwitchState() {
    }

    public static boolean enabled(Optional<String> primary, Optional<String> legacy) {
        Optional<String> value = primary == null ? Optional.empty() : primary;
        Optional<String> fallback = legacy == null ? Optional.empty() : legacy;
        return value.or(() -> fallback).map(KillSwitchState::parse).orElse(true);
    }

    public static boolean parse(String raw) {
        String normalized = raw == null ? "" : raw.trim().toLowerCase(Locale.ROOT);
        return "enabled".equals(normalized)
                || "enable".equals(normalized)
                || "on".equals(normalized)
                || "true".equals(normalized)
                || "1".equals(normalized);
    }
}
