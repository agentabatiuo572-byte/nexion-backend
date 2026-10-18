package ffdd.opsconsole.growth.domain;

import static org.assertj.core.api.Assertions.*;
import java.time.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class AccountWeeklyPeriodTest {
    @ParameterizedTest
    @CsvSource({"Asia/Tokyo,167,2026-10-25T15:00:00Z", "Asia/Shanghai,168,2026-10-25T16:00:00Z",
            "Asia/Ho_Chi_Minh,169,2026-10-25T17:00:00Z"})
    void bridgeKeepsOriginalEndAndDoesNotCreateOneHourRewardWeek(String zone,long hours,String end) {
        var period=AccountWeeklyPeriod.successor(LocalDate.of(2026,10,12),Instant.parse("2026-10-18T16:00:00Z"),zone);
        assertThat(period.localWeekStart()).isEqualTo(LocalDate.of(2026,10,19));
        assertThat(Duration.between(period.from(),period.until()).toHours()).isEqualTo(hours);
        assertThat(period.until()).isEqualTo(Instant.parse(end));
        assertThat(period.contains(period.from())).isTrue();
        assertThat(period.contains(period.until())).isFalse();
        assertThat(AccountWeeklyPeriod.instant(period.storageFrom())).isEqualTo(period.from());
        assertThat(AccountWeeklyPeriod.instant(period.storageUntil())).isEqualTo(period.until());
        assertThat(AccountWeeklyPeriod.regular(period.until(),zone).from()).isEqualTo(period.until());
    }
    @ParameterizedTest
    @CsvSource({"America/New_York,2026-03-08T12:00:00Z,167", "America/New_York,2026-11-01T12:00:00Z,169",
            "Europe/Berlin,2026-03-29T12:00:00Z,167", "Europe/Berlin,2026-10-25T12:00:00Z,169"})
    void dstUsesTwoLocalBoundaries(String zone,String instant,long hours) {
        var period=AccountWeeklyPeriod.regular(Instant.parse(instant),zone);
        assertThat(Duration.between(period.from(),period.until()).toHours()).isEqualTo(hours);
        assertThat(period.from().atZone(ZoneId.of(zone)).getDayOfWeek()).isEqualTo(DayOfWeek.MONDAY);
        assertThat(period.until().atZone(ZoneId.of(zone)).toLocalTime()).isEqualTo(LocalTime.MIDNIGHT);
    }
    @Test
    void changingAcrossDateLineAdvancesLabelAndKeepsAdjacentWindows() {
        var old=AccountWeeklyPeriod.regular(Instant.parse("2026-12-30T00:00:00Z"),"Pacific/Kiritimati");
        var next=AccountWeeklyPeriod.successor(old.localWeekStart(),old.until(),"Pacific/Pago_Pago");
        assertThat(next.localWeekStart()).isEqualTo(LocalDate.of(2027,1,4));
        assertThat(next.from()).isEqualTo(old.until());
        assertThat(Duration.between(next.from(),next.until()).toHours()).isEqualTo(193);
        assertThat(AccountWeeklyPeriod.regular(next.until(),next.zoneId()).localWeekStart()).isEqualTo(LocalDate.of(2027,1,11));
    }
    @Test
    void differentAccountsCanStillOccupyDifferentWeeksAtOneInstant() {
        Instant instant=Instant.parse("2026-10-25T16:30:00Z");
        assertThat(AccountWeeklyPeriod.regular(instant,"Asia/Shanghai").localWeekStart()).isEqualTo(LocalDate.of(2026,10,26));
        assertThat(AccountWeeklyPeriod.regular(instant,"Asia/Ho_Chi_Minh").localWeekStart()).isEqualTo(LocalDate.of(2026,10,19));
    }
}
