package ffdd.opsconsole.team.application;

import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.concurrent.atomic.AtomicReference;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.scheduling.support.CronExpression;
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
    private final LeadershipPoolConfigGuard settlementConfigGuard;
    private final LeadershipPoolConfigAlertService settlementConfigAlertService;
    private final AtomicReference<ZonedDateTime> lastTriggeredMinute = new AtomicReference<>();
    private final AtomicReference<String> lastInvalidCron = new AtomicReference<>();

    /** 每分钟读取运营权威键，命中动态 cron 时结算；部署属性不再覆盖业务配置。 */
    @Scheduled(fixedDelayString = "${nexion.f4.pool-schedule-poll-ms:30000}")
    public synchronized void weeklySettle() {
        ZonedDateTime now = ZonedDateTime.now(ZoneOffset.UTC).withSecond(0).withNano(0);
        ZonedDateTime next;
        try {
            LeadershipPoolConfigGuard.SettlementConfig config = settlementConfigGuard.requireValid();
            next = CronExpression.parse(config.springCron()).next(now.minusMinutes(1));
            lastInvalidCron.set(null);
        } catch (LeadershipPoolConfigGuard.ConfigUnavailableException failure) {
            // Deduplicate the poller alert by the non-sensitive failure signature. Manual attempts
            // still write their own audit entry at the settlement boundary.
            if (!failure.signature().equals(lastInvalidCron.get())) {
                settlementConfigAlertService.recordBlocked(failure, "scheduler");
                log.error("F4 leadership pool schedule disabled: key={} reason={} valueFingerprint={}",
                        failure.key(), failure.reason(), failure.valueFingerprint());
                lastInvalidCron.set(failure.signature());
            }
            return;
        }
        if (next == null || next.isAfter(now) || now.equals(lastTriggeredMinute.get())) {
            return;
        }
        try {
            // currentYearWeek 由 mapper 算(YEARWEEK(NOW,1));周日 23:59 仍属本周
            int settled = leadershipPoolService.injectAndSettleCurrentWeek();
            lastTriggeredMinute.set(now);
            log.info("F4 leadership pool weekly settle done: settled={}", settled);
        } catch (RuntimeException ex) {
            log.error("F4 leadership pool weekly settle failed: {}", ex.getMessage(), ex);
        }
    }

    static String normalizeCron(String configured) {
        String value = configured == null ? "" : configured.trim().replaceAll("\\s+", " ");
        int fields = value.isEmpty() ? 0 : value.split(" ").length;
        String springCron;
        if (fields == 5) springCron = "0 " + value;
        else if (fields == 6) springCron = value;
        else throw new IllegalArgumentException("F4_SETTLE_CRON_INVALID");
        try {
            CronExpression.parse(springCron);
            return springCron;
        } catch (IllegalArgumentException ex) {
            throw new IllegalArgumentException("F4_SETTLE_CRON_INVALID", ex);
        }
    }
}
