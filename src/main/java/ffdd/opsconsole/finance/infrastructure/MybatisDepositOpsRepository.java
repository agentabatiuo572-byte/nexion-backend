package ffdd.opsconsole.finance.infrastructure;

import ffdd.opsconsole.finance.domain.DepositAggregateView;
import ffdd.opsconsole.finance.domain.DepositBinRiskView;
import ffdd.opsconsole.finance.domain.DepositChargebackView;
import ffdd.opsconsole.finance.domain.DepositFlowView;
import ffdd.opsconsole.finance.domain.DepositOpsRepository;
import ffdd.opsconsole.finance.mapper.DepositOrderMapper;
import ffdd.opsconsole.finance.mapper.PaymentRecordMapper;
import ffdd.opsconsole.shared.api.PageResult;
import java.math.BigDecimal;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;

@Repository
@RequiredArgsConstructor
public class MybatisDepositOpsRepository implements DepositOpsRepository {
    private final DepositOrderMapper depositOrderMapper;
    private final PaymentRecordMapper paymentRecordMapper;

    @Override
    public List<DepositAggregateView> aggregateToday() {
        return depositOrderMapper.aggregateToday();
    }

    @Override
    public PageResult<DepositFlowView> pageFlows(Collection<String> statuses, Long userId, String keyword, int pageNum, int pageSize) {
        String trimmedKeyword = keyword == null ? null : keyword.trim();
        long total = depositOrderMapper.countFlows(statuses, userId, trimmedKeyword);
        List<DepositFlowView> rows = depositOrderMapper.pageFlows(statuses, userId, trimmedKeyword, pageSize, (pageNum - 1) * pageSize);
        return new PageResult<>(total, pageNum, pageSize, rows);
    }

    @Override
    public long cardPaidCountToday() {
        return paymentRecordMapper.cardPaidCountToday();
    }

    @Override
    public BigDecimal cardPaidAmountToday() {
        BigDecimal amount = paymentRecordMapper.cardPaidAmountToday();
        return amount == null ? BigDecimal.ZERO : amount;
    }

    @Override
    public List<DepositBinRiskView> failedPaymentRiskRows(int threshold) {
        return paymentRecordMapper.failedPaymentRiskRows(threshold);
    }

    @Override
    public List<DepositChargebackView> chargebacks() {
        return paymentRecordMapper.chargebacks();
    }

    @Override
    public Optional<DepositChargebackView> findChargeback(String caseNo) {
        return Optional.ofNullable(paymentRecordMapper.findChargeback(caseNo));
    }

    @Override
    public int markChargebackRefunded(String caseNo, String reason) {
        return paymentRecordMapper.markChargebackRefunded(caseNo, reason);
    }

    @Override
    public void seedD1FallbackData(Map<String, Long> userIds) {
        Long reviewer = userIds.get("usr_77D4");
        Long trusted = userIds.get("usr_31E8");
        Long active = userIds.get("usr_2231");
        Long frozen = userIds.get("usr_55B1");
        Long flagged = userIds.get("usr_8807");

        seedDeposit("D1-SEED-TRC20-001", reviewer, "USDT-TRC20", "D1SEEDTRC200001", "USDT", "1280.00",
                20, "CONFIRMED", true, true, false, null, 8);
        seedDeposit("D1-SEED-ERC20-001", trusted, "USDT-ERC20", "0xd1seederc200001", "USDT", "860.00",
                64, "CREDITED", true, true, false, null, 18);
        seedDeposit("D1-SEED-BTC-001", active, "BTC", "d1seedbtc0001", "USDT", "420.00",
                6, "CONFIRMED", true, true, false, null, 34);
        seedDeposit("D1-SEED-CARD-001", frozen, "Card", "D1SEEDCARD0001", "USDT", "540.00",
                1, "CONFIRMED", true, true, false, null, 46);
        seedDeposit("D1-SEED-ABNORMAL-001", flagged, "USDT-TRC20", "D1SEEDABNORMAL0001", "USDT", "188.00",
                0, "FAILED", false, false, true, "provider callback missing", 52);

        seedPayment("D1-SEED-PAY-CARD-001", "D1-SEED-CARD-001", frozen, "Checkout.com", "d1_seed_checkout_001",
                "540.00", "USD", "PAID", "evt_d1_seed_card_001", "VERIFIED", true, false, false,
                null, 1, true, null, 45);
        seedPayment("D1-SEED-CB-0001", "D1-SEED-CARD-CB-001", reviewer, "Stripe", "d1_seed_chargeback_001",
                "188.00", "USD", "CHARGEBACK", "evt_d1_seed_cb_001", "VERIFIED", true, false, false,
                "chargeback after ledger entry", 2, true, "chargeback dispute opened", 58);
        for (int i = 1; i <= 5; i++) {
            seedPayment("D1-SEED-PAY-DECLINE-" + i, "D1-SEED-CARD-FAIL-" + i, flagged, "Checkout.com",
                    "d1_seed_decline_" + i, "42.00", "USD", "FAILED", "evt_d1_seed_decline_" + i,
                    "3DS_TIMEOUT", false, true, false, "issuer declined", i, false,
                    "3DS challenge not completed", 12 + i);
        }
    }

    private void seedDeposit(String depositNo, Long userId, String chainName, String chainTxHash, String asset,
                             String amount, int confirmations, String status, boolean confirmed, boolean credited,
                             boolean failed, String failureReason, int minutesAgo) {
        if (userId == null) {
            return;
        }
        depositOrderMapper.insertD1SeedDeposit(
                depositNo,
                userId,
                chainName,
                chainTxHash,
                asset,
                new BigDecimal(amount),
                confirmations,
                status,
                confirmed,
                credited,
                failed,
                failureReason,
                minutesAgo);
    }

    private void seedPayment(String paymentNo, String orderNo, Long userId, String provider, String providerPaymentId,
                             String amountUsdt, String currency, String paymentStatus, String callbackEventId,
                             String signatureStatus, boolean paid, boolean failed, boolean expired,
                             String failureReason, int reconcileAttempts, boolean reconciled,
                             String lastReconcileError, int minutesAgo) {
        if (userId == null) {
            return;
        }
        paymentRecordMapper.insertD1SeedPayment(
                paymentNo,
                orderNo,
                userId,
                provider,
                providerPaymentId,
                new BigDecimal(amountUsdt),
                currency,
                paymentStatus,
                callbackEventId,
                signatureStatus,
                paid,
                failed,
                expired,
                failureReason,
                reconcileAttempts,
                reconciled,
                lastReconcileError,
                minutesAgo);
    }
}
