package ffdd.opsconsole.team.application;

import ffdd.opsconsole.team.domain.TeamCommissionRepository;
import ffdd.opsconsole.team.mapper.TeamCommissionMapper;
import ffdd.opsconsole.platform.facade.PlatformConfigFacade;
import ffdd.opsconsole.treasury.facade.TreasuryLedgerPostingFacade;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * F4 领导池结算引擎。
 *
 * <p>周结算(周日 23:59 UTC,poolSettleCron):平台周交易额 × leadershipPoolInjectRate(5%) 注入池
 * → 按 V3+ 用户的 leadership_votes 票权比例分配 → commission_event(leadership,UNLOCKED)+ D4。
 *
 * <p>PRD(落地规格 line234/130/737-739):leadershipPoolInjectRate 5%· V_VOTES{V3:1..V12:512}· poolSettleCron 周日 23:59 UTC· poolUnlockVRank V3+。
 *
 * <p><b>已实现</b>:injectAndSettle(weekCode) 注入+票权分配+幂等(weekKey 防重);settle(poolAmount,weekKey) 票权比例分配。
 * <b>待完善</b>:①injectRate 配置化(team.ui.F.pool.injectRate,默认 5%);②端点 POST /leadership-pool/settle(F4-MD2 手动);
 *  ③单元测试 + 运行时验证。
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class LeadershipPoolService {

    private static final BigDecimal ZERO = BigDecimal.ZERO.setScale(6);
    private static final int SCALE = 6;
    /** PRD 默认 leadershipPoolInjectRate 5%(line737);可配置 team.ui.F.pool.injectRate(0-1)。 */
    private static final BigDecimal DEFAULT_INJECT_RATE = new BigDecimal("0.05");
    private static final String CONFIG_KEY_INJECT_RATE = "team.ui.F.pool.injectRate";
    private static final String COMMISSION_LEADERSHIP = "leadership";
    private static final String CURRENCY_USDT = "USDT";
    private static final String STATUS_UNLOCKED = "UNLOCKED";
    /** F5 coolingDays 配置 key(PRD line231 默认30;读 commission/cooling-days)。 */
    private static final String CONFIG_KEY_COOLING_DAYS = "commission/cooling-days";
    private static final int DEFAULT_COOLING_DAYS = 30;

    private final TeamCommissionMapper teamCommissionMapper;
    private final TeamCommissionRepository commissionRepository;
    private final TreasuryLedgerPostingFacade ledgerPostingFacade;
    private final PlatformConfigFacade configFacade;

    /** Scheduler entry point: resolve the database-canonical ISO week at execution time. */
    @Transactional(rollbackFor = Exception.class)
    public int injectAndSettleCurrentWeek() {
        return injectAndSettle(teamCommissionMapper.currentYearWeek());
    }

    /** 读 team.ui.F.pool.injectRate(0-1),解析失败/越界回退默认 5%。 */
    private BigDecimal resolveInjectRate() {
        return configFacade.activeValue(CONFIG_KEY_INJECT_RATE)
                .map(v -> {
                    try { return new BigDecimal(v.trim()); }
                    catch (NumberFormatException e) { return null; }
                })
                .filter(b -> b.signum() >= 0 && b.compareTo(BigDecimal.ONE) <= 0)
                .orElse(DEFAULT_INJECT_RATE);
    }

    /** F5 coolingDays(读 commission/cooling-days,默认30;PRD line231)。 */
    private int resolveCoolingDays() {
        return configFacade.activeValue(CONFIG_KEY_COOLING_DAYS)
                .map(v -> {
                    try { return Integer.parseInt(v.trim()); }
                    catch (NumberFormatException e) { return null; }
                })
                .filter(d -> d >= 0)
                .orElse(DEFAULT_COOLING_DAYS);
    }

    /**
     * 注入 + 结算:平台周交易额 × 5% → 按票权分配给 V3+ 用户。幂等(同 weekKey 已结算则跳过)。
     *
     * @param weekCode ISO YEARWEEK 整数(如 202630)
     * @return 实际分得份额的用户数(-1 表示跳过/无数据)
     */
    @Transactional(rollbackFor = Exception.class)
    public int injectAndSettle(int weekCode) {
        String weekKey = String.valueOf(weekCode);
        // 幂等:同周已结算 → 跳过
        if (teamCommissionMapper.countLeadershipByWeek(weekKey) > 0) {
            log.info("F4 leadership pool week={} already settled, skip", weekKey);
            return -1;
        }
        BigDecimal weeklyVolume = teamCommissionMapper.weeklyPlatformVolume(weekCode);
        if (weeklyVolume == null || weeklyVolume.signum() <= 0) {
            log.info("F4 leadership pool week={} no platform volume, skip", weekKey);
            return -1;
        }
        BigDecimal poolAmount = weeklyVolume.multiply(resolveInjectRate()).setScale(SCALE, RoundingMode.HALF_UP);
        log.info("F4 leadership pool inject: week={} weeklyVolume={} × {} = poolAmount={}",
                weekKey, weeklyVolume, resolveInjectRate(), poolAmount);
        return settle(poolAmount, weekKey);
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
        if (poolAmountUsdt == null || poolAmountUsdt.signum() <= 0 || weekKey == null || weekKey.isBlank()) {
            return 0;
        }
        List<Map<String, Object>> voters = teamCommissionMapper.listV3PlusVoters();
        if (voters == null || voters.isEmpty()) {
            log.warn("F4 leadership pool settle: no V3+ voters, week={}", weekKey);
            return 0;
        }
        BigDecimal totalVotes = BigDecimal.ZERO;
        for (Map<String, Object> v : voters) {
            BigDecimal votes = asBigDecimal(v.get("votes"));
            if (votes != null && votes.signum() > 0) {
                totalVotes = totalVotes.add(votes);
            }
        }
        if (totalVotes.signum() <= 0) {
            log.warn("F4 leadership pool settle: total votes=0, week={}", weekKey);
            return 0;
        }
        int settled = 0;
        for (Map<String, Object> v : voters) {
            Long userId = asLong(v.get("userId"));
            BigDecimal votes = asBigDecimal(v.get("votes"));
            if (userId == null || votes == null || votes.signum() <= 0) {
                continue;
            }
            BigDecimal share = poolAmountUsdt.multiply(votes)
                    .divide(totalVotes, SCALE, RoundingMode.DOWN);
            if (share.signum() <= 0) {
                continue;
            }
            String remark = "F4 leadership pool | week=" + weekKey + " votes=" + votes + "/" + totalVotes;
            Long eventId = commissionRepository.insertCommissionEvent(
                    userId, COMMISSION_LEADERSHIP, null, CURRENCY_USDT,
                    share, ZERO, STATUS_UNLOCKED, resolveCoolingDays(), remark);
            if (eventId == null) {
                log.warn("F4 leadership commission_event insert failed: user={} week={}", userId, weekKey);
                continue;
            }
            ledgerPostingFacade.postLedgerEntry(
                    "F4-POOL-" + weekKey + "-" + eventId, userId, "TEAM_COMMISSION", CURRENCY_USDT,
                    "IN", share, "PENDING", "F4 leadership pool settle | " + remark);
            settled++;
        }
        log.info("F4 leadership pool settled: week={} pool={} voters={} settled={}",
                weekKey, poolAmountUsdt, voters.size(), settled);
        return settled;
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
