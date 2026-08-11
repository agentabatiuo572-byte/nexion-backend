package ffdd.opsconsole.team.application;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
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
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.time.ZonedDateTime;
import java.time.temporal.TemporalAdjusters;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
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
    private static final String CONFIG_KEY_TOP1_MAX_PCT = "team.ui.F.pool.top1MaxPct";
    private static final String CONFIG_KEY_TOP5_MAX_PCT = "team.ui.F.pool.top5MaxPct";
    private static final String CONFIG_KEY_PERIOD_PRIZE = "team.ui.F.pool.periodPrize";
    private static final String CONFIG_KEY_LEADERBOARD_POOL = "team.ui.F.leaderboard.poolUsd";
    private static final String CONFIG_KEY_LEADERBOARD_MIN = "team.ui.F.leaderboard.minUsd";
    private static final String CONFIG_KEY_LEADERBOARD_PAUSED = "team.ui.F.leaderboard.paused";
    private static final String CONFIG_KEY_LAST_SETTLED_PREFIX = "team.runtime.F.leaderboard.lastSettled.";
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String COMMISSION_LEADERSHIP = "leadership";
    private static final String COMMISSION_LEADERBOARD_PRIZE = "leaderboard_prize";
    private static final String CURRENCY_USDT = "USDT";
    private static final String STATUS_UNLOCKED = "UNLOCKED";

    private final TeamCommissionMapper teamCommissionMapper;
    private final TeamCommissionRepository commissionRepository;
    private final TreasuryLedgerPostingFacade ledgerPostingFacade;
    private final PlatformConfigFacade configFacade;
    private final AuditLogService auditLogService;
    private final EventOutboxService eventOutboxService;
    private final LeadershipPoolConfigGuard settlementConfigGuard;
    private final LeadershipPoolConfigAlertService settlementConfigAlertService;

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
        LeadershipPoolConfigGuard.SettlementConfig settlementConfig = requireSettlementConfig("settlement");
        String weekKey = String.valueOf(weekCode);
        teamCommissionMapper.ensureLeadershipSettlementMutex(weekCode);
        if (teamCommissionMapper.lockLeadershipSettlementMutex(weekCode) == null) {
            throw new IllegalStateException("F4_SETTLEMENT_MUTEX_UNAVAILABLE");
        }
        int leadershipSettled = 0;
        boolean leadershipSkipped = teamCommissionMapper.countLeadershipByWeek(weekKey) > 0;
        if (!leadershipSkipped) {
            BigDecimal weeklyVolume = teamCommissionMapper.weeklyPlatformVolume(weekCode);
            if (weeklyVolume == null || weeklyVolume.signum() <= 0) {
                leadershipSkipped = true;
                log.info("F4 leadership pool week={} no platform volume, skip leadership settlement", weekKey);
            } else {
                BigDecimal injectRate = settlementConfig.injectRate();
                BigDecimal poolAmount = weeklyVolume.multiply(injectRate).setScale(SCALE, RoundingMode.HALF_UP);
                BigDecimal monthPaid = safe(teamCommissionMapper.monthlyLeadershipAmount());
                BigDecimal remaining = settlementConfig.monthlyCap().subtract(monthPaid).max(BigDecimal.ZERO);
                poolAmount = poolAmount.min(remaining).setScale(SCALE, RoundingMode.DOWN);
                if (poolAmount.signum() <= 0) {
                    leadershipSkipped = true;
                    log.info("F4 leadership pool week={} blocked by monthly cap", weekKey);
                } else {
                    log.info("F4 leadership pool inject: week={} weeklyVolume={} × {} = poolAmount={}",
                            weekKey, weeklyVolume, injectRate, poolAmount);
                    leadershipSettled = settleTrusted(
                            poolAmount, weekKey, actorId, actorType, actorUsername, reason, settlementConfig);
                }
            }
        } else {
            log.info("F4 leadership pool week={} already settled, skip leadership settlement", weekKey);
        }
        if (leadershipSettled == 0 && leadershipSkipped) {
            return -1;
        }
        return leadershipSettled;
    }

    /** 每分钟补齐所有已结束周期；持久化 checkpoint 使午夜停机后仍可追赶。 */
    @Transactional(rollbackFor = Exception.class)
    public int settleClosedLeaderboardPeriods(ZonedDateTime nowUtc) {
        ZonedDateTime now = nowUtc.withSecond(0).withNano(0);
        LocalDate today = now.toLocalDate();
        int settled = catchUpDaily(today);
        settled += catchUpWeekly(today);
        settled += catchUpMonthly(today);
        return settled;
    }

    private int catchUpDaily(LocalDate today) {
        LocalDate latestClosed = today.minusDays(1);
        LocalDate last = configFacade.activeValue(CONFIG_KEY_LAST_SETTLED_PREFIX + "today")
                .map(this::parseDateOrNull).orElse(null);
        if (last == null) last = latestClosed.minusDays(1);
        int settled = 0;
        for (LocalDate period = last.plusDays(1); !period.isAfter(latestClosed); period = period.plusDays(1)) {
            settled += settleLeaderboardPrize("today", "today:" + period,
                    period.atStartOfDay(), period.plusDays(1).atStartOfDay(),
                    null, "SYSTEM", "SYSTEM", "排行榜日榜自动/补偿结算");
            persistLastSettled("today", period.toString());
        }
        return settled;
    }

    private int catchUpWeekly(LocalDate today) {
        LocalDate currentMonday = today.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
        LocalDate latestClosed = currentMonday.minusWeeks(1);
        LocalDate last = configFacade.activeValue(CONFIG_KEY_LAST_SETTLED_PREFIX + "week")
                .map(this::parseDateOrNull).orElse(null);
        if (last == null) last = latestClosed.minusWeeks(1);
        int settled = 0;
        for (LocalDate period = last.plusWeeks(1); !period.isAfter(latestClosed); period = period.plusWeeks(1)) {
            settled += settleLeaderboardPrize("week", "week:" + period,
                    period.atStartOfDay(), period.plusWeeks(1).atStartOfDay(),
                    null, "SYSTEM", "SYSTEM", "排行榜周榜自动/补偿结算");
            persistLastSettled("week", period.toString());
        }
        return settled;
    }

    private int catchUpMonthly(LocalDate today) {
        YearMonth latestClosed = YearMonth.from(today).minusMonths(1);
        YearMonth last = configFacade.activeValue(CONFIG_KEY_LAST_SETTLED_PREFIX + "month")
                .map(this::parseMonthOrNull).orElse(null);
        if (last == null) last = latestClosed.minusMonths(1);
        int settled = 0;
        for (YearMonth period = last.plusMonths(1); !period.isAfter(latestClosed); period = period.plusMonths(1)) {
            LocalDate start = period.atDay(1);
            settled += settleLeaderboardPrize("month", "month:" + period,
                    start.atStartOfDay(), period.plusMonths(1).atDay(1).atStartOfDay(),
                    null, "SYSTEM", "SYSTEM", "排行榜月榜自动/补偿结算");
            persistLastSettled("month", period.toString());
        }
        return settled;
    }

    private void persistLastSettled(String period, String value) {
        configFacade.upsertAdminValue(CONFIG_KEY_LAST_SETTLED_PREFIX + period, value,
                "TEXT", "team_runtime", "F16 leaderboard settlement checkpoint");
    }

    private LocalDate parseDateOrNull(String value) {
        try { return LocalDate.parse(value); } catch (RuntimeException ignored) { return null; }
    }

    private YearMonth parseMonthOrNull(String value) {
        try { return YearMonth.parse(value); } catch (RuntimeException ignored) { return null; }
    }

    /** allTime 无自动 reset，只能由 A2 审批后人工派发，且 lifetime key 保证只能成功一次。 */
    @Transactional(rollbackFor = Exception.class)
    public int settleApprovedLeaderboardPeriod(String period, String operator, String reason) {
        return settleApprovedLeaderboardPeriod(period, null, operator, reason);
    }

    @Transactional(rollbackFor = Exception.class)
    public int settleApprovedLeaderboardPeriod(String period, String requestedPeriodKey, String operator, String reason) {
        if (!A2ReplayContext.isReplaying()) throw new BizException(409, "A2_CONFIRMATION_REQUIRED");
        String canonical = period == null ? "" : period.trim();
        String normalizedReason = reason == null ? "" : reason.trim();
        if (!Set.of("today", "week", "month", "allTime").contains(canonical)) {
            throw new BizException(422, "F4_LEADERBOARD_PERIOD_INVALID");
        }
        if (normalizedReason.length() < 8) throw new BizException(422, "F4_SETTLEMENT_REASON_REQUIRED");
        LocalDate today = LocalDate.now(java.time.ZoneOffset.UTC);
        LocalDateTime from = null;
        LocalDateTime to = today.plusDays(1).atStartOfDay();
        String key;
        if ("today".equals(canonical)) {
            LocalDate date = parseDateOrNull(requestedPeriodKey);
            if (date == null || !date.isBefore(today)) throw new BizException(422, "F4_CLOSED_PERIOD_REQUIRED");
            from = date.atStartOfDay();
            to = date.plusDays(1).atStartOfDay();
            key = "today:" + date;
        } else if ("week".equals(canonical)) {
            LocalDate monday = parseDateOrNull(requestedPeriodKey);
            if (monday == null || monday.getDayOfWeek() != DayOfWeek.MONDAY
                    || monday.plusWeeks(1).isAfter(today)) throw new BizException(422, "F4_CLOSED_PERIOD_REQUIRED");
            from = monday.atStartOfDay();
            to = monday.plusWeeks(1).atStartOfDay();
            key = "week:" + monday;
        } else if ("month".equals(canonical)) {
            YearMonth month = parseMonthOrNull(requestedPeriodKey);
            if (month == null || !month.isBefore(YearMonth.from(today))) {
                throw new BizException(422, "F4_CLOSED_PERIOD_REQUIRED");
            }
            from = month.atDay(1).atStartOfDay();
            to = month.plusMonths(1).atDay(1).atStartOfDay();
            key = "month:" + month;
        } else {
            key = "allTime:lifetime";
        }
        return settleLeaderboardPrize(canonical, key, from, to, null, "ADMIN",
                operator == null || operator.isBlank() ? "unknown-admin" : operator.trim(), normalizedReason);
    }

    private int settleLeaderboardPrize(
            String period,
            String settlementKey,
            LocalDateTime fromInclusive,
            LocalDateTime toExclusive,
            Long actorId,
            String actorType,
            String actorUsername,
            String reason) {
        if (configBoolean(CONFIG_KEY_LEADERBOARD_PAUSED, false)
                || teamCommissionMapper.countLeaderboardBySettlementKey(settlementKey) > 0) {
            return 0;
        }
        int mutexKey = settlementKey.hashCode() & Integer.MAX_VALUE;
        teamCommissionMapper.ensureLeadershipSettlementMutex(mutexKey);
        if (teamCommissionMapper.lockLeadershipSettlementMutex(mutexKey) == null) {
            throw new IllegalStateException("F4_LEADERBOARD_MUTEX_UNAVAILABLE");
        }
        if (teamCommissionMapper.countLeaderboardBySettlementKey(settlementKey) > 0) return 0;
        BigDecimal prizePool = "week".equals(period)
                ? configMoney(CONFIG_KEY_LEADERBOARD_POOL).filter(value -> value.signum() > 0)
                        .orElseGet(() -> configuredPeriodPrize(period))
                : configuredPeriodPrize(period);
        if (prizePool.signum() <= 0) return 0;
        BigDecimal minVolume = configMoney(CONFIG_KEY_LEADERBOARD_MIN).orElse(BigDecimal.ZERO);
        int topN = switch (period) {
            case "today" -> 20;
            case "week" -> 50;
            default -> 100;
        };
        List<Map<String, Object>> candidates = commissionRepository.leaderboardCandidates(
                period, fromInclusive, toExclusive, minVolume, topN);
        if (candidates.isEmpty()) return 0;

        BigDecimal top1Cap = prizePool.multiply(configPercent(CONFIG_KEY_TOP1_MAX_PCT, new BigDecimal("25")));
        BigDecimal top5Cap = prizePool.multiply(configPercent(CONFIG_KEY_TOP5_MAX_PCT, new BigDecimal("60")));
        BigDecimal totalWeight = BigDecimal.valueOf((long) candidates.size() * (candidates.size() + 1) / 2);
        BigDecimal allocated = BigDecimal.ZERO;
        BigDecimal allocatedTop5 = BigDecimal.ZERO;
        int settled = 0;
        for (int index = 0; index < candidates.size(); index++) {
            Long userId = asLong(candidates.get(index).get("userId"));
            if (userId == null) continue;
            BigDecimal weight = BigDecimal.valueOf(candidates.size() - index);
            BigDecimal share = prizePool.multiply(weight).divide(totalWeight, SCALE, RoundingMode.DOWN);
            if (index == 0) share = share.min(top1Cap);
            if (index < 5) share = share.min(top5Cap.subtract(allocatedTop5).max(BigDecimal.ZERO));
            if (share.signum() <= 0) continue;
            String remark = "F4 leaderboard | settlementKey=" + settlementKey + "| period=" + period
                    + " rank=" + (index + 1)
                    + " top1MaxPct=" + configText(CONFIG_KEY_TOP1_MAX_PCT, "25")
                    + " top5MaxPct=" + configText(CONFIG_KEY_TOP5_MAX_PCT, "60");
            Long eventId = commissionRepository.insertCommissionEvent(
                    userId, COMMISSION_LEADERBOARD_PRIZE, null, CURRENCY_USDT,
                    share, ZERO, STATUS_UNLOCKED, 0, remark);
            if (eventId == null) throw new IllegalStateException("F4_LEADERBOARD_EVENT_INSERT_FAILED");
            ledgerPostingFacade.postLedgerEntry("F4-LB-" + settlementKey + "-" + eventId, userId,
                    "TEAM_COMMISSION", CURRENCY_USDT, "IN", share, "SUCCESS", remark);
            eventOutboxService.publish("LEADERBOARD_PRIZE", "F4-LB-" + settlementKey + "-" + eventId,
                    "commission.paid", linked("userId", userId, "amount", share,
                            "period", period, "settlementKey", settlementKey));
            allocated = allocated.add(share);
            if (index < 5) allocatedTop5 = allocatedTop5.add(share);
            settled++;
        }
        auditLogService.recordRequired(AuditLogWriteRequest.builder()
                .action("F4_LEADERBOARD_PRIZE_SETTLED")
                .resourceType("LEADERBOARD_SETTLEMENT")
                .resourceId(settlementKey)
                .bizNo("F4-LB-" + settlementKey)
                .actorId(actorId).actorType(actorType).actorUsername(actorUsername)
                .result("SUCCESS").riskLevel("HIGH")
                .detail(linked("period", period, "settlementKey", settlementKey,
                        "configuredPool", prizePool, "allocated", allocated,
                        "minUsd", minVolume, "settledUsers", settled, "reason", reason))
                .build());
        return settled;
    }

    BigDecimal configuredPeriodPrize(String period) {
        return configFacade.activeValue(CONFIG_KEY_PERIOD_PRIZE).map(value -> {
            try {
                JsonNode amount = JSON.readTree(value).get(period);
                return amount != null && amount.isNumber() ? amount.decimalValue() : BigDecimal.ZERO;
            } catch (Exception ignored) {
                return BigDecimal.ZERO;
            }
        }).orElse(BigDecimal.ZERO);
    }

    private Optional<BigDecimal> configMoney(String key) {
        return configFacade.activeValue(key).map(LeadershipPoolService::parseMoney)
                .filter(value -> value != null && value.signum() >= 0);
    }

    private BigDecimal configPercent(String key, BigDecimal fallbackPct) {
        return configMoney(key).orElse(fallbackPct).min(new BigDecimal("100")).movePointLeft(2);
    }

    private boolean configBoolean(String key, boolean fallback) {
        return configFacade.activeValue(key).map(value -> Set.of("on", "true", "1", "paused")
                .contains(value.trim().toLowerCase())).orElse(fallback);
    }

    private String configText(String key, String fallback) {
        return configFacade.activeValue(key).filter(value -> !value.isBlank()).orElse(fallback);
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
        LeadershipPoolConfigGuard.SettlementConfig settlementConfig = requireSettlementConfig("direct-settlement");
        return settleTrusted(
                poolAmountUsdt, weekKey, null, "SYSTEM", "SYSTEM", "测试/补偿结算", settlementConfig);
    }

    private int settleTrusted(
            BigDecimal poolAmountUsdt,
            String weekKey,
            Long actorId,
            String actorType,
            String actorUsername,
            String reason,
            LeadershipPoolConfigGuard.SettlementConfig settlementConfig) {
        if (poolAmountUsdt == null || poolAmountUsdt.signum() <= 0 || weekKey == null || weekKey.isBlank()) {
            return 0;
        }
        int unlockRank = settlementConfig.unlockRank();
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
                        "configVersion", settlementConfig.version(),
                        "configFingerprint", settlementConfig.fingerprint(),
                        "totalVotes", totalVotes,
                        "settledUsers", settled,
                        "reason", reason))
                .build());
        log.info("F4 leadership pool settled: week={} pool={} voters={} settled={}",
                weekKey, poolAmountUsdt, voters.size(), settled);
        return settled;
    }

    private LeadershipPoolConfigGuard.SettlementConfig requireSettlementConfig(String source) {
        try {
            return settlementConfigGuard.requireValid();
        } catch (LeadershipPoolConfigGuard.ConfigUnavailableException failure) {
            settlementConfigAlertService.recordBlocked(failure, source);
            throw new BizException(503, "F4_SETTLEMENT_CONFIG_UNAVAILABLE");
        }
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
