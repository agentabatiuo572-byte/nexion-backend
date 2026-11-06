package ffdd.opsconsole.platform.facade;

import ffdd.opsconsole.common.boundary.DomainFacade;
import java.util.Map;
import java.util.Optional;

public interface PlatformConfigFacade extends DomainFacade {
    Optional<String> activeValue(String configKey);

    default SessionLifetimes sessionLifetimes() {
        return SessionLifetimes.from(activeValue(SessionLifetimes.ACCESS_KEY).orElse(null),
                activeValue(SessionLifetimes.REFRESH_KEY).orElse(null));
    }

    record SessionLifetimes(int accessHours, int refreshDays) {
        public static final String ACCESS_KEY = "auth.session.access_ttl_hours";
        public static final String REFRESH_KEY = "auth.session.refresh_ttl_days";
        public static final SessionLifetimes DEFAULT = new SessionLifetimes(4, 30);

        public static SessionLifetimes from(String access, String refresh) {
            return new SessionLifetimes(bounded(access, 4, 1, 24), bounded(refresh, 30, 7, 90));
        }

        private static int bounded(String raw, int fallback, int min, int max) {
            if (raw == null) return fallback;
            try {
                int value = Integer.parseInt(raw.trim());
                return value >= min && value <= max ? value : fallback;
            } catch (NumberFormatException ex) {
                return fallback;
            }
        }
    }

    /** Reads the current value under a database row lock when called inside a transaction. */
    default Optional<String> activeValueForUpdate(String configKey) {
        return activeValue(configKey);
    }

    void upsertAdminValue(String configKey, String configValue, String valueType, String configGroup, String remark);

    /**
     * Atomically inserts an operator-editable value only when the key has never existed.
     * Disabled and soft-deleted rows still count as existing and are never overwritten.
     */
    default boolean insertAdminValueIfMissing(
            String configKey, String configValue, String valueType, String configGroup, String remark) {
        return false;
    }

    default Map<String, String> activeValuesByGroup(String configGroup) {
        return Map.of();
    }
}
