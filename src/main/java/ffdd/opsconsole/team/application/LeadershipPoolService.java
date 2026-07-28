package ffdd.opsconsole.team.application;

import ffdd.opsconsole.platform.application.A2ReplayContext;
import ffdd.opsconsole.shared.audit.AuditLogService;
import ffdd.opsconsole.shared.audit.AuditLogWriteRequest;
import ffdd.opsconsole.shared.exception.BizException;
import ffdd.opsconsole.shared.outbox.EventOutboxService;
import ffdd.opsconsole.team.domain.TeamCommissionRepository;
import ffdd.opsconsole.team.mapper.TeamCommissionMapper;
import ffdd.opsconsole.platform.facade.PlatformConfigFacade;
import ffdd.opsconsole.treasury.facade.TreasuryLedgerPostingFacade;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * F4 领导池结算引擎。
 *
 * <p>周结算(周日 23:59 UTC,poolSettleCron):平台周交易额 × F.pool.ratio 注入池
 * → 按配置门槛以上用户的 leadership_votes 票权比例分配
 * → commission_event(leadership,UNLOCKED)+ D4 + A4。
 *
 * <p>PRD(落地规格 line234/130/737-739):leadershipPoolInjectRate 5%· V_VOTES{V3:1..V12:512}· poolSettleCron 周日 23:59 UTC· poolUnlockVRank V3+。
 *
 * <p>同一周先锁定数据库 mutex,再检查历史事件,避免并发 check-then-insert 双发。
 * 运营提前结算只允许从 A2 审批 replay 进入;调度器仍可作为 SYSTEM 入口执行。
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class LeadershipPoolService {

    private static final BigDecimal ZERO = BigDecimal.ZERO.setScale(6);
    private static final int SCALE = 6;
    /** PRD 默认 leadershipPoolInjectRate 5%;运营端以百分数配置 F.pool.ratio。 */
    private static final BigDecimal DEFAULT_INJECT_RATE = new BigDecimal("0.05");
    private static final String CONFIG_KEY_INJECT_RATE = "team.ui.F.pool.ratio";
    private static final String CONFIG_KEY_UNLOCK_RANK = "team.ui.F.pool.unlockVRank";
    private static final String CONFIG_KEY_MONTHLY_CAP = "team.ui.F.pool.monthlyCap";
    private static final String COMMISSION_LEADERSHIP = "leadership";
    private static final String CURRENCY_USDT = "USDT";
    private static final String STATUS_UNLOCKED = "UNLOCKED";

    private final TeamCommissionMapper teamCommissionMapper;
    private final TeamCommissionRepository commissionRepository;
    private final TreasuryLedgerPostingFacade ledgerPostingFacade;
    private final PlatformConfigFacade configFacade;
    private final AuditLogService auditLogService;
    private final EventOutboxService eventOutboxService;

    /** Scheduler entry point: resolve the database-canonical ISO week at execution time. */
    @Transactional(rollbackFor = Exception.class)
    public int injectAndSettleCurrentWeek() {
        return injectAndSettleTrusted(
                teamCommissionMapper.currentYearWeek(), null, "SYSTEM", "SYSTEM", "系统周期结算");
    }

    /** F4 提前结算是资金动作,必须由 A2 双人审批后的 replay 调用。 */
    @Transactional(rollbackFor = Exception.class)
    public int injectAndSettleApprovedCurrentWeek(String operator, String reason) {
        if (!A2ReplayContext.isReplaying()) {
            throw new BizException(409, "A2_CONFIRMATION_REQUIRED");
        }
        String normalizedReason = reason == null ? "" : reason.trim();
        if (normalizedReason.length() < 8) {
            throw new BizException(422, "F4_SETTLEMENT_REASON_REQUIRED");
        }
        String normalizedOperator = operator == null || operator.isBlank() ? "unknown-admin" : operator.trim();
        return injectAndSettleTrusted(
                teamCommissionMapper.currentYearWeek(), null, "ADMIN", normalizedOperator, normalizedReason);
    }

    /** 读 team.ui.F.pool.ratio(0-100%),兼容 5 / 5% / 0.05,解析失败回退默认 5%。 */
    private BigDecimal resolveInjectRate() {
        return configFacade.activeValue(CONFIG_KEY_INJECT_RATE)
                .map(v -> {
                    try {
                        String raw = v.trim();
                        boolean percent = raw.endsWith("%");
                        BigDecimal parsed = new BigDecimal(percent ? raw.substring(0, raw.length() - 1).trim() : raw);
                        if (percent || parsed.compareTo(BigDecimal.ONE) > 0) {
                            parsed = parsed.movePointLeft(2);
                        }
                        return parsed;
                    }
                    catch (NumberFormatException e) { return null; }
                })
                .filter(b -> b.signum() >= 0 && b.compareTo(BigDecimal.ONE) <= 0)
                .orElse(DEFAULT_INJECT_RATE);
    }

    private int resolveUnlockRank() {
        return configFacade.activeValue(CONFIG_KEY_UNLOCK_RANK)
                .map(v -> {
                    try { return Integer.parseInt(v.trim().toUpperCase().replaceFirst("^V", "")); }
                    catch (NumberFormatException e) { return null; }
                })
                .filter(rank -> rank >= 1 && rank <= 12)
                .orElse(3);
    }

    private Optional<BigDecimal> resolveMonthlyCap() {
        return configFacade.activeValue(CONFIG_KEY_MONTHLY_CAP)
                .map(LeadershipPoolService::parseMoney)
                .filter(cap -> cap != null && cap.signum() >= 0);
    }

    /**
     * 注入 + 结算:平台周交易额 × 5% → 按票权分配给 V3+ 用户。幂等(同 weekKey 已结算则跳过)。
     *
     * @param weekCode ISO YEARWEEK 整数(如 202630)
     * @return 实际分得份额的用户数(-1 表示跳过/无数据)
     */
    @Transactional(rollbackFor = Exception.class)
    public int injectAndSettle(int weekCode) {
        return injectAndSettleTrusted(weekCode, null, "SYSTEM", "SYSTEM", "测试/补偿结算");
    }

    private int injectAndSettleTrusted(
            int weekCode, Long actorId, String actorType, String actorUsername, String reason) {
        String weekKey = String.valueOf(weekCode);
        teamCommissionMapper.ensureLeadershipSettlementMutex(weekCode);
        if (teamCommissionMapper.lockLeadershipSettlementMutex(weekCode) == null) {
            throw new IllegalStateException("F4_SETTLEMENT_MUTEX_UNAVAILABLE");
        }
        if (teamCommissionMapper.countLeadershipByWeek(weekKey) > 0) {
            log.info("F4 leadership pool week={} already settled, skip", weekKey);
            return -1;
        }
        BigDecimal weeklyVolume = teamCommissionMapper.weeklyPlatformVolume(weekCode);
        if (weeklyVolume == null || weeklyVolume.signum() <= 0) {
            log.info("F4 leadership pool week={} no platform volume, skip", weekKey);
            return -1;
        }
        BigDecimal injectRate = resolveInjectRate();
        BigDecimal poolAmount = weeklyVolume.multiply(injectRate).setScale(SCALE, RoundingMode.HALF_UP);
        Optional<BigDecimal> monthlyCap = resolveMonthlyCap();
        if (monthlyCap.isPresent()) {
            BigDecimal monthPaid = safe(teamCommissionMapper.monthlyLeadershipAmount());
            BigDecimal remaining = monthlyCap.get().subtract(monthPaid).max(BigDecimal.ZERO);
            poolAmount = poolAmount.min(remaining).setScale(SCALE, RoundingMode.DOWN);
        }
        if (poolAmount.signum() <= 0) {
            log.info("F4 leadership pool week={} blocked by monthly cap", weekKey);
            return -1;
        }
        log.info("F4 leadership pool inject: week={} weeklyVolume={} × {} = poolAmount={}",
                weekKey, weeklyVolume, injectRate, poolAmount);
        return settleTrusted(poolAmount, weekKey, actorId, actorType, actorUsername, reason);
    }

    /**
     * 按票权比例把池金额分配给 V3+ 用户。
     *
     * @param poolAmountUsdt 本周池金额(USDT)
     * @param weekKey        周标识(幂等 + 审计)
     * @return 实际分得份额的用户数
     */
    @Transactional(rollbackFor = Exception.class)
    public int settle(BigDecimal poolAmountUsdt, String weekKey) {
        return settleTrusted(poolAmountUsdt, weekKey, null, "SYSTEM", "SYSTEM", "测试/补偿结算");
    }

    private int settleTrusted(
            BigDecimal poolAmountUsdt,
            String weekKey,
            Long actorId,
            String actorType,
            String actorUsername,
            String reason) {
        if (poolAmountUsdt == null || poolAmountUsdt.signum() <= 0 || weekKey == null || weekKey.isBlank()) {
            return 0;
        }
        int unlockRank = resolveUnlockRank();
        List<Map<String, Object>> voters = teamCommissionMapper.listLeadershipVoters(unlockRank);
        if (voters == null || voters.isEmpty()) {
            log.warn("F4 leadership pool settle: no V{}+ voters, week={}", unlockRank, weekKey);
            return 0;
        }
        List<Voter> eligible = new ArrayList<>();
        BigDecimal totalVotes = BigDecimal.ZERO;
        for (Map<String, Object> v : voters) {
            Long userId = asLong(v.get("userId"));
            BigDecimal votes = asBigDecimal(v.get("votes"));
            if (userId != null && votes != null && votes.signum() > 0) {
                eligible.add(new Voter(userId, votes));
                totalVotes = totalVotes.add(votes);
            }
        }
        if (totalVotes.signum() <= 0) {
            log.warn("F4 leadership pool settle: total votes=0, week={}", weekKey);
            return 0;
        }
        int settled = 0;
        BigDecimal allocated = ZERO;
        for (int index = 0; index < eligible.size(); index++) {
            Voter voter = eligible.get(index);
            BigDecimal share = index == eligible.size() - 1
                    ? poolAmountUsdt.subtract(allocated).setScale(SCALE, RoundingMode.DOWN)
                    : poolAmountUsdt.multiply(voter.votes()).divide(totalVotes, SCALE, RoundingMode.DOWN);
            if (share.signum() <= 0) {
                continue;
            }
            String remark = "F4 leadership pool | week=" + weekKey
                    + " votes=" + voter.votes() + "/" + totalVotes;
            Long eventId = commissionRepository.insertCommissionEvent(
                    voter.userId(), COMMISSION_LEADERSHIP, null, CURRENCY_USDT,
                    share, ZERO, STATUS_UNLOCKED, 0, remark);
            if (eventId == null) {
                throw new IllegalStateException("F4_COMMISSION_EVENT_INSERT_FAILED");
            }
            ledgerPostingFacade.postLedgerEntry(
                    "F4-POOL-" + weekKey + "-" + eventId, voter.userId(), "TEAM_COMMISSION", CURRENCY_USDT,
                    "IN", share, "SUCCESS", "F4 leadership pool settle | " + remark);
            eventOutboxService.publish(
                    "LEADERSHIP_COMMISSION",
                    "F4-POOL-" + weekKey + "-" + eventId,
                    "commission.paid",
                    linked(
                            "userId", voter.userId(),
                            "kind", COMMISSION_LEADERSHIP,
                            "currency", CURRENCY_USDT,
                            "amount", share,
                            "commissionEventId", eventId));
            allocated = allocated.add(share);
            settled++;
        }
        if (allocated.compareTo(poolAmountUsdt.setScale(SCALE, RoundingMode.DOWN)) != 0) {
            throw new IllegalStateException("F4_POOL_ALLOCATION_MISMATCH");
        }
        auditLogService.recordRequired(AuditLogWriteRequest.builder()
                .action("F4_LEADERSHIP_POOL_SETTLED")
                .resourceType("LEADERSHIP_POOL_SETTLEMENT")
                .resourceId(weekKey)
                .bizNo("F4-POOL-" + weekKey)
                .actorId(actorId)
                .actorType(actorType)
                .actorUsername(actorUsername)
                .result("SUCCESS")
                .riskLevel("HIGH")
                .detail(linked(
                        "week", weekKey,
                        "poolAmountUsdt", poolAmountUsdt,
                        "unlockRank", "V" + unlockRank,
                        "totalVotes", totalVotes,
                        "settledUsers", settled,
                        "reason", reason))
                .build());
        log.info("F4 leadership pool settled: week={} pool={} voters={} settled={}",
                weekKey, poolAmountUsdt, voters.size(), settled);
        return settled;
    }

    private static BigDecimal parseMoney(String value) {
        if (value == null || value.isBlank()) return null;
        String raw = value.trim().toUpperCase().replace("$", "").replace(",", "");
        BigDecimal multiplier = BigDecimal.ONE;
        if (raw.endsWith("M")) {
            multiplier = new BigDecimal("1000000");
            raw = raw.substring(0, raw.length() - 1);
        } else if (raw.endsWith("K")) {
            multiplier = new BigDecimal("1000");
            raw = raw.substring(0, raw.length() - 1);
        }
        try {
            return new BigDecimal(raw.trim()).multiply(multiplier);
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    private static BigDecimal safe(BigDecimal value) {
        return value == null ? BigDecimal.ZERO : value;
    }

    private static Map<String, Object> linked(Object... values) {
        Map<String, Object> result = new LinkedHashMap<>();
        for (int i = 0; i < values.length; i += 2) {
            result.put(String.valueOf(values[i]), values[i + 1]);
        }
        return result;
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

    private record Voter(Long userId, BigDecimal votes) {
    }
}
