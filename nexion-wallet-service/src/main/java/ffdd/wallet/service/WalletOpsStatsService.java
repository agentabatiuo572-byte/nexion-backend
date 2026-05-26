package ffdd.wallet.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import ffdd.wallet.domain.DepositOrder;
import ffdd.wallet.domain.ExchangeOrder;
import ffdd.wallet.domain.WalletLedger;
import ffdd.wallet.domain.WithdrawalOrder;
import ffdd.wallet.mapper.DepositOrderMapper;
import ffdd.wallet.mapper.ExchangeOrderMapper;
import ffdd.wallet.mapper.WalletLedgerMapper;
import ffdd.wallet.mapper.WithdrawalOrderMapper;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.beans.factory.annotation.Autowired;
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
    private final WalletLedgerMapper ledgerMapper;
    private final Clock clock;

    @Autowired
    public WalletOpsStatsService(
            DepositOrderMapper depositOrderMapper,
            WithdrawalOrderMapper withdrawalOrderMapper,
            ExchangeOrderMapper exchangeOrderMapper,
            WalletLedgerMapper ledgerMapper) {
        this(depositOrderMapper, withdrawalOrderMapper, exchangeOrderMapper, ledgerMapper, Clock.systemDefaultZone());
    }

    WalletOpsStatsService(
            DepositOrderMapper depositOrderMapper,
            WithdrawalOrderMapper withdrawalOrderMapper,
            ExchangeOrderMapper exchangeOrderMapper,
            WalletLedgerMapper ledgerMapper,
            Clock clock) {
        this.depositOrderMapper = depositOrderMapper;
        this.withdrawalOrderMapper = withdrawalOrderMapper;
        this.exchangeOrderMapper = exchangeOrderMapper;
        this.ledgerMapper = ledgerMapper;
        this.clock = clock;
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

    private Map<String, Object> section(Object... pairs) {
        Map<String, Object> values = new LinkedHashMap<>();
        for (int i = 0; i + 1 < pairs.length; i += 2) {
            values.put(String.valueOf(pairs[i]), pairs[i + 1]);
        }
        return values;
    }
}
