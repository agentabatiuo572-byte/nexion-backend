package ffdd.opsconsole.shared.config;

import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;

/** Storage DATETIME values use a single deployment-wide wall clock. */
public final class StorageTime {
    public static final String ENV_KEY = "NEXION_TIME_STORAGE_ZONE";
    public static final String SHANGHAI = "Asia/Shanghai";
    public static final String VIETNAM = "Asia/Ho_Chi_Minh";
    public static final ZoneId ZONE = resolveZone(System.getenv(ENV_KEY));

    private StorageTime() { }

    public static ZoneId resolveZone(String value) {
        String zone = value == null || value.isBlank() ? SHANGHAI : value.trim();
        if (!SHANGHAI.equals(zone) && !VIETNAM.equals(zone)) {
            throw new IllegalArgumentException(ENV_KEY + " must be " + SHANGHAI + " or " + VIETNAM);
        }
        return ZoneId.of(zone);
    }

    public static ZoneOffset offset() {
        return ZONE.getRules().getOffset(Instant.now());
    }
}
