package ffdd.opsconsole.shared.config;

import static org.assertj.core.api.Assertions.assertThat;
import java.time.*;
import org.junit.jupiter.api.Test;

class VietnamBusinessPeriodTest {
    @Test void preservesHistoricBoundariesAndBridgesTheOpenWeekAndMonth() {
        assertThat(VietnamBusinessPeriod.storageBoundary(LocalDate.of(2026,10,12))).isEqualTo("2026-10-12T00:00:00");
        assertThat(VietnamBusinessPeriod.storageBoundary(LocalDate.of(2026,10,19))).isEqualTo("2026-10-19T01:00:00");
        assertThat(VietnamBusinessPeriod.storageBoundary(LocalDate.of(2026,10,1))).isEqualTo("2026-10-01T00:00:00");
        assertThat(VietnamBusinessPeriod.storageBoundary(LocalDate.of(2026,11,1))).isEqualTo("2026-11-01T01:00:00");
        assertThat(Duration.between(VietnamBusinessPeriod.storageBoundary(LocalDate.of(2026,10,12)),
                VietnamBusinessPeriod.storageBoundary(LocalDate.of(2026,10,19))).toHours()).isEqualTo(169);
    }

    @Test void vietnamMidnightMapsToOneAmStorageAndSeventeenUtc() {
        for (LocalDate date : new LocalDate[]{LocalDate.of(2026,10,19),LocalDate.of(2026,11,1),LocalDate.of(2027,1,1)}) {
            Instant boundary = VietnamBusinessPeriod.storageBoundary(date).atZone(VietnamBusinessPeriod.STORAGE_ZONE).toInstant();
            assertThat(boundary).isEqualTo(date.minusDays(1).atTime(17,0).toInstant(ZoneOffset.UTC));
            assertThat(VietnamBusinessPeriod.dateAt(boundary.minusNanos(1))).isEqualTo(date.minusDays(1));
            assertThat(VietnamBusinessPeriod.dateAt(boundary)).isEqualTo(date);
        }
    }

    @Test void transitionIsMonotonicAndEachInstantBelongsToExactlyOneDay() {
        for (LocalDate date = LocalDate.of(2026,10,17); date.isBefore(LocalDate.of(2026,11,3)); date=date.plusDays(1)) {
            LocalDateTime from = VietnamBusinessPeriod.storageBoundary(date);
            LocalDateTime to = VietnamBusinessPeriod.storageBoundary(date.plusDays(1));
            assertThat(from).isBefore(to);
            assertThat(VietnamBusinessPeriod.calendarTime(from).toLocalDate()).isEqualTo(date);
            assertThat(VietnamBusinessPeriod.calendarTime(to.minusNanos(1)).toLocalDate()).isEqualTo(date);
            assertThat(VietnamBusinessPeriod.calendarTime(to).toLocalDate()).isEqualTo(date.plusDays(1));
        }
    }

    @Test void isoYearAndHistoricalWeekIdentityRemainStable() {
        assertThat(VietnamBusinessPeriod.weekKey(LocalDateTime.parse("2026-10-19T00:59:59"))).isEqualTo("WEEK:2026-W42");
        assertThat(VietnamBusinessPeriod.weekKey(LocalDateTime.parse("2026-10-19T01:00:00"))).isEqualTo("WEEK:2026-W43");
        assertThat(VietnamBusinessPeriod.weekKey(LocalDateTime.parse("2027-01-04T00:59:59"))).isEqualTo("WEEK:2026-W53");
        assertThat(VietnamBusinessPeriod.weekKey(LocalDateTime.parse("2027-01-04T01:00:00"))).isEqualTo("WEEK:2027-W01");
        assertThat(VietnamBusinessPeriod.weekKey(LocalDateTime.parse("2026-10-12T00:00:00"))).isEqualTo("WEEK:2026-W42");
    }
}
