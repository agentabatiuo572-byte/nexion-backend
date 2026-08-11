package ffdd.opsconsole.team.application;

import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** F16 日/周/月排行榜到期自动派奖；allTime 按产品约束仅走 A2 人工派发。 */
@Component
@RequiredArgsConstructor
@Slf4j
public class LeaderboardPrizeScheduler {

    private final LeadershipPoolService leadershipPoolService;

    @Scheduled(cron = "0 * * * * *", zone = "UTC")
    public void settleClosedPeriods() {
        try {
            int settled = leadershipPoolService.settleClosedLeaderboardPeriods(ZonedDateTime.now(ZoneOffset.UTC));
            if (settled > 0) log.info("F16 closed leaderboard periods settled: users={}", settled);
        } catch (RuntimeException ex) {
            log.error("F16 closed leaderboard period settlement failed: {}", ex.getMessage(), ex);
        }
    }
}
