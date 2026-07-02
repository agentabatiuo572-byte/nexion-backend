package ffdd.opsconsole.finance.domain;

import ffdd.opsconsole.shared.api.PageResult;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface DepositOpsRepository {
    List<DepositAggregateView> aggregateToday();

    PageResult<DepositFlowView> pageFlows(Collection<String> statuses, Long userId, String keyword, int pageNum, int pageSize);

    long cardPaidCountToday();

    BigDecimal cardPaidAmountToday();

    boolean hasReconciliationWriteoff(String channelCode, LocalDate reconcileDate);

    void writeoffReconciliation(String channelCode, LocalDate reconcileDate, String operator, String reason, String idempotencyKey);

    List<DepositBinRiskView> failedPaymentRiskRows(int threshold);

    List<DepositChargebackView> chargebacks();

    Optional<DepositChargebackView> findChargeback(String caseNo);

    int markChargebackRefunded(String caseNo, String reason);

}
