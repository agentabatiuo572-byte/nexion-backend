package ffdd.opsconsole.team.application;

import ffdd.opsconsole.shared.outbox.EventOutboxService;
import ffdd.opsconsole.team.domain.TeamCommissionRepository;
import ffdd.opsconsole.team.mapper.TeamCommissionMapper;
import ffdd.opsconsole.treasury.facade.TreasuryLedgerPostingFacade;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * F5 commission event 自动解锁调度器。
 *
 * <p>定时扫描 COOLING 且 unlock_at 已到期(coolingDays 满)的 commission_event → UNLOCKED:
 * <ul>
 *   <li>updateCommissionStatus(COOLING→UNLOCKED),复用 OpsTeamService 状态机(COOLING→UNLOCKED 合法转换);</li>
 *   <li>发 COMMISSION_UNLOCKED 事件(H3 canonical quest trigger + 审计);</li>
 *   <li>D4 台账 IN/PENDING(用户可提应付入账,对齐 OpsTeamService postCommissionLedgerIfStatusChanged)。</li>
 * </ul>
 *
 * <p>coolingDays 默认 30(F2/F3 insert 时 unlock_at = NOW()+30d);PRD 落地规格 line231 coolingDays(30,域独立)。
 * 后续可配置化(nx_config_item commission/cooling-days)。
 *
 * <p>单条解锁失败不中断整批;幂等(updateCommissionStatus 已 UNLOCKED 则 0 行)。
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class CommissionEventUnlockScheduler {

    private static final int BATCH_SIZE = 500;
    private static final String STATUS_UNLOCKED = "UNLOCKED";

    private final TeamCommissionMapper teamCommissionMapper;
    private final TeamCommissionRepository commissionRepository;
    private final EventOutboxService eventOutboxService;
    private final TreasuryLedgerPostingFacade ledgerPostingFacade;

    /** 自动解锁(fixedDelay 10min,可配 nexion.f5.unlock-delay-ms)。 */
    @Scheduled(fixedDelayString = "${nexion.f5.unlock-delay-ms:600000}",
               initialDelayString = "${nexion.f5.unlock-initial-delay-ms:300000}")
    public void autoUnlockCoolingDue() {
        List<Map<String, Object>> due = teamCommissionMapper.listCoolingDueForUnlock(BATCH_SIZE);
        if (due == null || due.isEmpty()) {
            log.debug("F5 unlock: no COOLING commission due");
            return;
        }
        int unlocked = 0;
        int errors = 0;
        for (Map<String, Object> row : due) {
            Long eventId = asLong(row.get("id"));
            Long userId = asLong(row.get("userId"));
            BigDecimal amount = asBigDecimal(row.get("amountUsdt"));
            String currency = String.valueOf(row.getOrDefault("currency", "USDT"));
            if (eventId == null || userId == null) {
                continue;
            }
            try {
                if (!commissionRepository.updateCommissionStatus("CM-" + eventId, STATUS_UNLOCKED)) {
                    // 已非 COOLING(并发手动处置)或 0 行 → 跳过
                    continue;
                }
                // COMMISSION_UNLOCKED 事件(H3 canonical quest trigger + 审计链)
                eventOutboxService.publish(
                        "COMMISSION", String.valueOf(eventId), "COMMISSION_UNLOCKED",
                        Map.of("user_id", userId, "commission_event_id", eventId));
                // D4 台账 IN/PENDING(UNLOCKED=用户可提应付入账)
                if (amount != null && amount.signum() > 0) {
                    ledgerPostingFacade.postLedgerEntry(
                            "F5-AUTO-UNLOCK-" + eventId, userId, "TEAM_COMMISSION", currency,
                            "IN", amount, "PENDING", "F5 auto unlock commission | eventId=" + eventId);
                }
                unlocked++;
            } catch (RuntimeException ex) {
                errors++;
                log.warn("F5 auto unlock failed: eventId={} err={}", eventId, ex.getMessage());
            }
        }
        log.info("F5 unlock done: scanned={} unlocked={} errors={}", due.size(), unlocked, errors);
    }

    private static Long asLong(Object v) {
        if (v == null) return null;
        if (v instanceof Number n) return n.longValue();
        try { return Long.valueOf(v.toString()); } catch (NumberFormatException e) { return null; }
    }

    private static BigDecimal asBigDecimal(Object v) {
        if (v == null) return null;
        if (v instanceof BigDecimal b) return b;
        if (v instanceof Number n) return BigDecimal.valueOf(n.doubleValue());
        try { return new BigDecimal(v.toString()); } catch (NumberFormatException e) { return null; }
    }
}
