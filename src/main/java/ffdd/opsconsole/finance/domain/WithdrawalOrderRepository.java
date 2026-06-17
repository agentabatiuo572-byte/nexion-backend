package ffdd.opsconsole.finance.domain;

import java.util.Optional;

public interface WithdrawalOrderRepository {
    Optional<WithdrawalOrderView> findByWithdrawalNo(String withdrawalNo);

    void updateStatus(String withdrawalNo, String status, String failureReason);
}
