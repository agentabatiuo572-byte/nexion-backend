package ffdd.opsconsole.finance.infrastructure;


import lombok.RequiredArgsConstructor;
import ffdd.opsconsole.finance.domain.WithdrawalOrderRepository;
import ffdd.opsconsole.finance.domain.WithdrawalOrderView;
import ffdd.opsconsole.finance.mapper.WithdrawalOrderMapper;
import ffdd.opsconsole.shared.api.PageResult;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.stereotype.Repository;

@Repository
@RequiredArgsConstructor
public class MybatisWithdrawalOrderRepository implements WithdrawalOrderRepository {
    private final WithdrawalOrderMapper mapper;

    @Override
    public PageResult<WithdrawalOrderView> page(
            String status,
            Long userId,
            String keyword,
            BigDecimal minAmount,
            BigDecimal maxAmount,
            Integer minRiskScore,
            int pageNum,
            int pageSize) {
        String trimmedKeyword = keyword == null ? null : keyword.trim();
        long total = mapper.countPage(status, userId, trimmedKeyword, minAmount, maxAmount, minRiskScore);
        List<WithdrawalOrderView> records = mapper.page(status, userId, trimmedKeyword, minAmount, maxAmount, minRiskScore, pageSize, (pageNum - 1) * pageSize);
        return new PageResult<>(total, pageNum, pageSize, records);
    }

    @Override
    public Optional<WithdrawalOrderView> findByWithdrawalNo(String withdrawalNo) {
        return Optional.ofNullable(mapper.findByWithdrawalNo(withdrawalNo));
    }

    @Override
    public void updateStatus(String withdrawalNo, String status, String failureReason) {
        mapper.updateStatus(withdrawalNo, status, failureReason);
    }

    @Override
    public void seedD2FallbackData(Map<String, Long> userIds) {
        seedWithdrawal("D2-SEED-WD-90408", userIds.get("usr_8807"), "USDT", "USDT-TRC20",
                "8200.00", "8.00", "TD2SeedTarget90408", null, "REVIEWING",
                false, false, false, null, 0, 30, null, 9);
        seedWithdrawal("D2-SEED-WD-90412", userIds.get("usr_31E8"), "USDT", "USDT-TRC20",
                "2600.00", "2.60", "TD2SeedTarget90412", null, "DELAYED",
                false, false, false, "large withdrawal delayed for risk callback", 0, 75,
                "waiting risk route WR-02", 26);
        seedWithdrawal("D2-SEED-WD-90391", userIds.get("usr_2231"), "USDT", "USDT-ERC20",
                "1100.00", "5.00", "0xd2SeedTarget90391", null, "PENDING_CHAIN",
                false, false, false, null, 1, 10, null, 44);
        seedWithdrawal("D2-SEED-WD-90312", userIds.get("usr_55B1"), "USDT", "USDT-TRC20",
                "320.00", "1.00", "TD2SeedTarget90312", "TX-D2-SEED-90312", "SUCCESS",
                true, true, false, null, 1, null, null, 88);
    }

    private void seedWithdrawal(String withdrawalNo, Long userId, String asset, String chain, String amount,
                                String fee, String targetAddress, String chainTxHash, String status,
                                boolean chainSubmitted, boolean completed, boolean failed, String failureReason,
                                int broadcastAttempts, Integer nextBroadcastMinutes, String lastBroadcastError,
                                int minutesAgo) {
        if (userId == null) {
            return;
        }
        mapper.insertD2SeedWithdrawal(
                withdrawalNo,
                userId,
                asset,
                chain,
                new BigDecimal(amount),
                new BigDecimal(fee),
                targetAddress,
                chainTxHash,
                status,
                chainSubmitted,
                completed,
                failed,
                failureReason,
                broadcastAttempts,
                nextBroadcastMinutes,
                lastBroadcastError,
                minutesAgo);
    }
}
