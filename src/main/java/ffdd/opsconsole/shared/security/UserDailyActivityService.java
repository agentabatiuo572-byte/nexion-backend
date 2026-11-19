package ffdd.opsconsole.shared.security;

import ffdd.opsconsole.platform.facade.PlatformConfigFacade;
import ffdd.opsconsole.shared.config.BusinessCalendar;
import ffdd.opsconsole.shared.i18n.InterfaceLanguages;
import ffdd.opsconsole.shared.outbox.EventOutboxService;
import ffdd.opsconsole.shared.security.mapper.AuthSessionMapper;
import ffdd.opsconsole.user.infrastructure.UserEntity;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class UserDailyActivityService {
    private final AuthSessionMapper sessions;
    private final EventOutboxService outbox;
    private final PlatformConfigFacade config;

    @Transactional
    public void record(UserEntity user) {
        LocalDateTime seenAt = LocalDateTime.now(BusinessCalendar.STORAGE_ZONE).truncatedTo(ChronoUnit.MILLIS);
        BusinessCalendar.Track calendar = BusinessCalendar.legacy();
        LocalDate date = calendar.dateAtStorage(seenAt);
        Long userId = user.getId();
        // A pre-deploy B32 row can already exist for the current business date.
        if (sessions.recordDailyUserActivity(date, userId, seenAt.truncatedTo(ChronoUnit.SECONDS)) == 0
                && sessions.claimDailyActivityEvent(date, userId) == 0) return;

        LocalDateTime registeredAt = user.getCreatedAt();
        int accountAge = (int) Math.max(0, ChronoUnit.MONTHS.between(registeredAt, seenAt));
        String cohort = calendar.weekKey(registeredAt).substring("WEEK:".length());
        String phase = config.activeValue("growth.phase.current").orElse("P1");
        outbox.publishUserEventAt("USER_DAILY_ACTIVITY", userId + ":" + date, "app.dau",
                userId, phase, accountAge, cohort, seenAt,
                Map.of("event_ts", seenAt.atZone(BusinessCalendar.STORAGE_ZONE).toInstant().toEpochMilli(),
                        "locale", InterfaceLanguages.baseCode(user.getLanguage())));
    }
}
