package ffdd.opsconsole.user.domain;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/** Server-authoritative device data exposed to a read-only impersonation session. */
public record UserReadonlyDeviceView(
        String instanceNo,
        String name,
        String deviceType,
        Integer generation,
        String status,
        BigDecimal hashrate,
        BigDecimal dailyUsdt,
        BigDecimal dailyNex,
        LocalDateTime lastSeenAt) {
}
