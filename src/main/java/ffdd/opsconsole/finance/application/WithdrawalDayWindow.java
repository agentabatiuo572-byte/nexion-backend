package ffdd.opsconsole.finance.application;

import ffdd.opsconsole.shared.config.BusinessCalendar;
import ffdd.opsconsole.shared.config.StorageTime;
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;

/** D5 uses a Vietnam calendar day; database DATETIME values use the shared storage zone. */
record WithdrawalDayWindow(LocalDateTime fromInclusive, LocalDateTime toExclusive, long resetAt) {
    private static final ZoneId ZONE = ZoneId.of(StorageTime.VIETNAM);

    static WithdrawalDayWindow at(Clock clock) {
        LocalDate date = clock.instant().atZone(ZONE).toLocalDate();
        LocalDate nextDate = date.plusDays(1);
        return new WithdrawalDayWindow(
                storageStart(date),
                storageStart(nextDate),
                nextDate.atStartOfDay(ZONE).toInstant().toEpochMilli());
    }

    static LocalDateTime storageStart(LocalDate date) {
        return BusinessCalendar.switchStorageAt(date, ZONE, List.of());
    }
}
