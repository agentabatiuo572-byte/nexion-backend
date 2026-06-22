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
}
