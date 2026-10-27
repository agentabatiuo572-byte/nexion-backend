package ffdd.opsconsole.platform.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import ffdd.opsconsole.platform.mapper.BusinessCalendarMapper;
import ffdd.opsconsole.shared.audit.AuditLogService;
import ffdd.opsconsole.shared.config.BusinessCalendar;
import ffdd.opsconsole.shared.idempotency.AdminIdempotencyService;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class BusinessCalendarAppViewTest {
    private final BusinessCalendarService service = new BusinessCalendarService(mock(BusinessCalendarMapper.class),
            mock(AuditLogService.class), mock(AdminIdempotencyService.class), Clock.systemUTC());

    @AfterEach void restoreSeed() { BusinessCalendar.replaceVersions(BusinessCalendar.seed()); }

    @Test void seedTracksAreReturnedAsUtcInstantsWithFixedOffsets() {
        var tracks = service.appView().getData().tracks();
        assertThat(tracks.keySet()).containsExactly(BusinessCalendar.LEGACY, BusinessCalendar.GROWTH_TEAM);
        assertThat(tracks.get(BusinessCalendar.LEGACY)).containsExactly(
                new BusinessCalendarService.AppTrackVersion("Asia/Shanghai", 480, "1969-12-31T16:00:00Z"));
        assertThat(tracks.get(BusinessCalendar.GROWTH_TEAM)).containsExactly(
                new BusinessCalendarService.AppTrackVersion("Asia/Shanghai", 480, "1969-12-31T16:00:00Z"),
                new BusinessCalendarService.AppTrackVersion("Asia/Ho_Chi_Minh", 420, "2026-10-18T16:00:00Z"));
    }

    @Test void aScheduledGlobalVersionAppearsOnBothTracksInOrder() {
        var versions = new ArrayList<>(BusinessCalendar.seed());
        LocalDate date = LocalDate.of(2026, 12, 1);
        versions.add(new BusinessCalendar.Version(BusinessCalendar.GLOBAL, ZoneId.of("Asia/Tokyo"), date, date.atStartOfDay().minusHours(1)));
        BusinessCalendar.replaceVersions(versions);
        var tracks = service.appView().getData().tracks();
        assertThat(tracks.get(BusinessCalendar.LEGACY)).last()
                .isEqualTo(new BusinessCalendarService.AppTrackVersion("Asia/Tokyo", 540, "2026-11-30T15:00:00Z"));
        assertThat(tracks.get(BusinessCalendar.GROWTH_TEAM)).last()
                .isEqualTo(new BusinessCalendarService.AppTrackVersion("Asia/Tokyo", 540, "2026-11-30T15:00:00Z"));
    }
}
