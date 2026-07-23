package ffdd.opsconsole.team.application;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * F4 领导池周结算调度器。
 *
 * <p>周日 23:59 UTC(poolSettleCron,PRD line739)触发:平台本周交易额 × injectRate(5%) 注入池
 * → 按 V3+ 用户票权比例分配 → commission_event leadership。
 *
 * <p>幂等:injectAndSettle 内 countLeadershipByWeek 防同周重复结算。
 * 周一 00:00 自动开新周(weekCode = YEARWEEK(NOW,1) 自然递进)。
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class LeadershipPoolSettleScheduler {

    private final LeadershipPoolService leadershipPoolService;

    /** 周日 23:59 UTC 结算(poolSettleCron)。可手动触发端点提前结算。 */
    @Scheduled(cron = "${nexion.f4.pool-settle-cron:0 59 23 * * 0}", zone = "UTC")
    public void weeklySettle() {
        try {
            // currentYearWeek 由 mapper 算(YEARWEEK(NOW,1));周日 23:59 仍属本周
            int settled = leadershipPoolService.injectAndSettleCurrentWeek();
            log.info("F4 leadership pool weekly settle done: settled={}", settled);
        } catch (RuntimeException ex) {
            log.error("F4 leadership pool weekly settle failed: {}", ex.getMessage(), ex);
        }
    }
}
