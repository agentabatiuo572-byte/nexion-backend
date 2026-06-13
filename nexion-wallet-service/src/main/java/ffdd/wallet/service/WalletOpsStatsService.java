package ffdd.wallet.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import ffdd.wallet.domain.DepositOrder;
import ffdd.wallet.domain.ExchangeOrder;
import ffdd.wallet.domain.WalletLedger;
import ffdd.wallet.domain.WithdrawalOrder;
import ffdd.wallet.mapper.DepositOrderMapper;
import ffdd.wallet.mapper.ExchangeOrderMapper;
import ffdd.wallet.mapper.NexLockOrderMapper;
import ffdd.wallet.mapper.StakingPositionMapper;
import ffdd.wallet.mapper.UserWalletMapper;
import ffdd.wallet.mapper.WalletLedgerMapper;
import ffdd.wallet.mapper.WithdrawalOrderMapper;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

@Service
public class WalletOpsStatsService {
    private static final int DEFAULT_DAYS = 7;
    private static final int MAX_DAYS = 90;
    private static final String STATUS_PENDING = "PENDING";
    private static final String STATUS_SUCCESS = "SUCCESS";
    private static final String STATUS_DEAD = "DEAD";
    private static final String STATUS_PENDING_CHAIN = "PENDING_CHAIN";
    private static final String STATUS_CHAIN_SUBMITTED = "CHAIN_SUBMITTED";
    private static final String STATUS_FAILED = "FAILED";
    private static final String STATUS_COMPLETED = "COMPLETED";
    private static final String STATUS_REVIEWING = "REVIEWING";
    private static final String STATUS_REJECTED = "REJECTED";
    private static final String DIRECTION_IN = "IN";
    private static final String DIRECTION_OUT = "OUT";

    private final DepositOrderMapper depositOrderMapper;
    private final WithdrawalOrderMapper withdrawalOrderMapper;
    private final ExchangeOrderMapper exchangeOrderMapper;
    private final UserWalletMapper userWalletMapper;
    private final StakingPositionMapper stakingPositionMapper;
    private final NexLockOrderMapper nexLockOrderMapper;
    private final WalletLedgerMapper ledgerMapper;
    private final Clock clock;
    private final BigDecimal reserveUsd;
    private final BigDecimal nexUsdRate;
    private final BigDecimal redlinePct;
    private final BigDecimal healthyPct;

    @Autowired
    public WalletOpsStatsService(
            DepositOrderMapper depositOrderMapper,
            WithdrawalOrderMapper withdrawalOrderMapper,
            ExchangeOrderMapper exchangeOrderMapper,
            UserWalletMapper userWalletMapper,
            StakingPositionMapper stakingPositionMapper,
            NexLockOrderMapper nexLockOrderMapper,
            WalletLedgerMapper ledgerMapper,
            @Value("${nexion.wallet.dual-ledger.reserve-usd:5000}") BigDecimal reserveUsd,
            @Value("${nexion.wallet.dual-ledger.nex-usd-rate:0.17}") BigDecimal nexUsdRate,
            @Value("${nexion.wallet.dual-ledger.redline-pct:85}") BigDecimal redlinePct,
            @Value("${nexion.wallet.dual-ledger.healthy-pct:100}") BigDecimal healthyPct) {
        this(
                depositOrderMapper,
                withdrawalOrderMapper,
                exchangeOrderMapper,
                userWalletMapper,
                stakingPositionMapper,
                nexLockOrderMapper,
                ledgerMapper,
                Clock.systemDefaultZone(),
                reserveUsd,
                nexUsdRate,
                redlinePct,
                healthyPct);
    }

    WalletOpsStatsService(
            DepositOrderMapper depositOrderMapper,
            WithdrawalOrderMapper withdrawalOrderMapper,
            ExchangeOrderMapper exchangeOrderMapper,
            UserWalletMapper userWalletMapper,
            StakingPositionMapper stakingPositionMapper,
            NexLockOrderMapper nexLockOrderMapper,
            WalletLedgerMapper ledgerMapper,
            Clock clock,
            BigDecimal reserveUsd,
            BigDecimal nexUsdRate,
            BigDecimal redlinePct,
            BigDecimal healthyPct) {
        this.depositOrderMapper = depositOrderMapper;
        this.withdrawalOrderMapper = withdrawalOrderMapper;
        this.exchangeOrderMapper = exchangeOrderMapper;
        this.userWalletMapper = userWalletMapper;
        this.stakingPositionMapper = stakingPositionMapper;
        this.nexLockOrderMapper = nexLockOrderMapper;
        this.ledgerMapper = ledgerMapper;
        this.clock = clock;
        this.reserveUsd = safe(reserveUsd);
        this.nexUsdRate = safe(nexUsdRate);
        this.redlinePct = safe(redlinePct);
        this.healthyPct = safe(healthyPct);
    }

    public Map<String, Object> summary(int days) {
        int normalizedDays = normalizeDays(days);
        LocalDateTime now = LocalDateTime.now(clock);
        LocalDateTime since = now.minusDays(normalizedDays - 1L).toLocalDate().atStartOfDay();

        Map<String, Object> response = section(
                "service", "nexion-wallet-service",
                "days", normalizedDays,
                "startAt", since,
                "endAt", now);
        response.put("deposits", section(
                "total", countDeposits(since, null),
                "success", countDeposits(since, STATUS_SUCCESS),
                "pending", countDeposits(since, STATUS_PENDING),
                "dead", countDeposits(since, STATUS_DEAD)));
        response.put("withdrawals", section(
                "total", countWithdrawals(since, null),
                "pendingChain", countWithdrawals(since, STATUS_PENDING_CHAIN),
                "chainSubmitted", countWithdrawals(since, STATUS_CHAIN_SUBMITTED),
                "success", countWithdrawals(since, STATUS_SUCCESS),
                "failed", countWithdrawals(since, STATUS_FAILED),
                "dead", countWithdrawals(since, STATUS_DEAD)));
        response.put("exchanges", section(
                "total", countExchanges(since, null),
                "completed", countExchanges(since, STATUS_COMPLETED),
                "reviewing", countExchanges(since, STATUS_REVIEWING),
                "rejected", countExchanges(since, STATUS_REJECTED)));
        response.put("ledger", section(
                "total", countLedgers(since, null),
                "credits", countLedgers(since, DIRECTION_IN),
                "debits", countLedgers(since, DIRECTION_OUT)));
        return response;
    }

    public Map<String, Object> dualLedger() {
        LocalDateTime now = LocalDateTime.now(clock);
        LocalDateTime current24hStart = now.minusHours(24);
        LocalDateTime prev24hStart = now.minusHours(48);

        BigDecimal queueBacklogUsd = safe(withdrawalOrderMapper.sumActiveQueueUsdt());
        long queueBacklogCount = nullToZero(withdrawalOrderMapper.countActiveQueue());
        BigDecimal userBalanceUsd = safe(userWalletMapper.sumUsdtAvailable());
        BigDecimal walletPendingUsd = safe(userWalletMapper.sumPendingWithdraw());
        BigDecimal stakingPrincipalUsd = safe(stakingPositionMapper.sumActivePrincipalUsdt());
        BigDecimal stakingInterestUsd = safe(stakingPositionMapper.sumActiveInterestUsdt());
        BigDecimal nexLiabilityUsd = safe(userWalletMapper.sumNexAvailable())
                .add(safe(nexLockOrderMapper.sumActiveAmountNex()))
                .add(safe(nexLockOrderMapper.sumActiveRewardNex()))
                .multiply(nexUsdRate);
        BigDecimal commissionCoolingUsd = safe(ledgerMapper.sumPendingCommissionUsdt());

        List<Map<String, Object>> accounts = new ArrayList<>();
        accounts.add(account("balance", "可提余额", userBalanceUsd, "--admin-cat-1", "nx_user_wallet.usdt_available"));
        accounts.add(account("stake_principal", "USDT 质押本金", stakingPrincipalUsd, "--admin-cat-2", "nx_staking_position.amount_usdt"));
        accounts.add(account("stake_interest", "质押应付利息", stakingInterestUsd, "--admin-cat-3", "nx_staking_position.estimated_interest_usdt"));
        accounts.add(account("genesis_div", "Genesis 日分红承诺", BigDecimal.ZERO, "--admin-cat-4", "pending genesis payable table not linked"));
        accounts.add(account("nexv2", "NEX / NEX v2 未来兑付", nexLiabilityUsd, "--admin-cat-5", "nx_user_wallet.nex_available + nx_nex_lock_order at configured NEX rate"));
        accounts.add(account("withdraw_queue", "待提现队列", queueBacklogUsd, "--admin-cat-6", "nx_withdrawal_order active queue"));
        accounts.add(account("commission_cool", "佣金冷却未解锁", commissionCoolingUsd, "--admin-cat-7", "nx_wallet_ledger pending commission"));
        accounts.add(account("lock_other", "钱包待处理提现", walletPendingUsd, "--admin-cat-8", "nx_user_wallet.pending_withdraw"));

        BigDecimal liabilitiesUsd = accounts.stream()
                .map(account -> (BigDecimal) account.get("amount"))
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal coverageRatio = pct(reserveUsd, liabilitiesUsd);
        BigDecimal netFlow24hUsd = safe(ledgerMapper.sumNetUsdtFlowBetween(current24hStart, now));
        BigDecimal prevNetFlow24hUsd = safe(ledgerMapper.sumNetUsdtFlowBetween(prev24hStart, current24hStart));
        BigDecimal avgRiskScore = safe(withdrawalOrderMapper.avgActiveQueueRiskScore()).setScale(0, RoundingMode.HALF_UP);

        Map<String, Object> response = section(
                "service", "nexion-wallet-service",
                "generatedAt", now,
                "sources", List.of(
                        "nx_user_wallet",
                        "nx_wallet_ledger",
                        "nx_withdrawal_order",
                        "nx_staking_position",
                        "nx_nex_lock_order",
                        "nexion.wallet.dual-ledger.* config"));
        response.put("snapshot", section(
                "reserveUsd", scaleMoney(reserveUsd),
                "liabilitiesUsd", scaleMoney(liabilitiesUsd),
                "coverageRatio", scalePct(coverageRatio),
                "redlinePct", scalePct(redlinePct),
                "healthyPct", scalePct(healthyPct),
                "netFlow24hUsd", scaleMoney(netFlow24hUsd),
                "queueBacklogCount", queueBacklogCount,
                "queueBacklogUsd", scaleMoney(queueBacklogUsd),
                "avgRiskScore", avgRiskScore.longValue(),
                "coverageSeries", coverageSeries(now, liabilitiesUsd)));
        response.put("accounts", accounts.stream().map(this::scaleAccount).toList());
        response.put("prev", section(
                "reserveUsd", scaleMoney(reserveUsd.subtract(prevNetFlow24hUsd)),
                "netFlow24hUsd", scaleMoney(prevNetFlow24hUsd),
                "queueBacklogCount", queueBacklogCount,
                "avgRiskScore", avgRiskScore.longValue()));
        return response;
    }

    private long countDeposits(LocalDateTime since, String status) {
        Long count = depositOrderMapper.selectCount(new LambdaQueryWrapper<DepositOrder>()
                .eq(DepositOrder::getIsDeleted, 0)
                .ge(DepositOrder::getCreatedAt, since)
                .eq(StringUtils.hasText(status), DepositOrder::getStatus, status));
        return nullToZero(count);
    }

    private long countWithdrawals(LocalDateTime since, String status) {
        Long count = withdrawalOrderMapper.selectCount(new LambdaQueryWrapper<WithdrawalOrder>()
                .eq(WithdrawalOrder::getIsDeleted, 0)
                .ge(WithdrawalOrder::getCreatedAt, since)
                .eq(StringUtils.hasText(status), WithdrawalOrder::getStatus, status));
        return nullToZero(count);
    }

    private long countExchanges(LocalDateTime since, String status) {
        Long count = exchangeOrderMapper.selectCount(new LambdaQueryWrapper<ExchangeOrder>()
                .eq(ExchangeOrder::getIsDeleted, 0)
                .ge(ExchangeOrder::getCreatedAt, since)
                .eq(StringUtils.hasText(status), ExchangeOrder::getStatus, status));
        return nullToZero(count);
    }

    private long countLedgers(LocalDateTime since, String direction) {
        Long count = ledgerMapper.selectCount(new LambdaQueryWrapper<WalletLedger>()
                .eq(WalletLedger::getIsDeleted, 0)
                .ge(WalletLedger::getCreatedAt, since)
                .eq(StringUtils.hasText(direction), WalletLedger::getDirection, direction));
        return nullToZero(count);
    }

    private int normalizeDays(int days) {
        if (days < 1) {
            return DEFAULT_DAYS;
        }
        return Math.min(days, MAX_DAYS);
    }

    private long nullToZero(Long count) {
        return count == null ? 0 : count;
    }

    private BigDecimal safe(BigDecimal value) {
        return value == null ? BigDecimal.ZERO : value;
    }

    private BigDecimal pct(BigDecimal numerator, BigDecimal denominator) {
        if (denominator == null || denominator.compareTo(BigDecimal.ZERO) == 0) {
            return numerator.compareTo(BigDecimal.ZERO) > 0 ? BigDecimal.valueOf(100) : BigDecimal.ZERO;
        }
        return numerator.multiply(BigDecimal.valueOf(100)).divide(denominator, 6, RoundingMode.HALF_UP);
    }

    private BigDecimal scaleMoney(BigDecimal value) {
        return safe(value).setScale(2, RoundingMode.HALF_UP);
    }

    private BigDecimal scalePct(BigDecimal value) {
        return safe(value).setScale(2, RoundingMode.HALF_UP);
    }

    private Map<String, Object> account(String key, String label, BigDecimal amount, String catVar, String source) {
        return section(
                "key", key,
                "label", label,
                "amount", safe(amount),
                "catVar", catVar,
                "source", source);
    }

    private Map<String, Object> scaleAccount(Map<String, Object> account) {
        Map<String, Object> scaled = new LinkedHashMap<>(account);
        scaled.put("amount", scaleMoney((BigDecimal) account.get("amount")));
        return scaled;
    }

    private List<BigDecimal> coverageSeries(LocalDateTime now, BigDecimal liabilitiesUsd) {
        List<BigDecimal> series = new ArrayList<>();
        LocalDateTime cursor = now.minusHours(24);
        BigDecimal runningReserve = reserveUsd;
        for (int i = 0; i < 8; i++) {
            LocalDateTime next = cursor.plusHours(3);
            BigDecimal windowNet = safe(ledgerMapper.sumNetUsdtFlowBetween(cursor, next));
            runningReserve = runningReserve.add(windowNet);
            series.add(scalePct(pct(runningReserve, liabilitiesUsd)));
            cursor = next;
        }
        return series;
    }

    private Map<String, Object> section(Object... pairs) {
        Map<String, Object> values = new LinkedHashMap<>();
        for (int i = 0; i + 1 < pairs.length; i += 2) {
            values.put(String.valueOf(pairs[i]), pairs[i + 1]);
        }
        return values;
    }
}
