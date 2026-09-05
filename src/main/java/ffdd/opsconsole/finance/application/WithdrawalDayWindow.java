package ffdd.opsconsole.finance.application;

import ffdd.opsconsole.shared.config.DateTimeFormatConfig;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneId;

/** D5 uses a Vietnam calendar day; database DATETIME values use the shared storage zone. */
record WithdrawalDayWindow(LocalDateTime fromInclusive, LocalDateTime toExclusive, long resetAt) {
    static WithdrawalDayWindow at(Clock clock) {
        var zone = ZoneId.of("Asia/Ho_Chi_Minh");
        var date = clock.instant().atZone(zone).toLocalDate();
        var start = date.atStartOfDay(zone);
        var end = date.plusDays(1).atStartOfDay(zone);
        return new WithdrawalDayWindow(
                start.withZoneSameInstant(DateTimeFormatConfig.BUSINESS_ZONE).toLocalDateTime(),
                end.withZoneSameInstant(DateTimeFormatConfig.BUSINESS_ZONE).toLocalDateTime(),
                end.toInstant().toEpochMilli());
    }
}
