package ffdd.opsconsole.treasury.facade;

import ffdd.opsconsole.common.boundary.DomainFacade;
import java.math.BigDecimal;
import java.time.LocalDateTime;

public interface TreasuryLedgerPostingFacade extends DomainFacade {
    void postLedgerEntry(String bizNo, Long userId, String bizType, String asset, String direction,
                         BigDecimal amount, String status, String remark);

    default void settleBankWithdrawalReserve(String withdrawalNo, BigDecimal amount, long providerId, LocalDateTime now) {
        throw new UnsupportedOperationException("BANK_RESERVE_SETTLEMENT_NOT_IMPLEMENTED");
    }

    default void reverseLegacyBankWithdrawalReserve(String withdrawalNo, BigDecimal amount, LocalDateTime now) {
        throw new UnsupportedOperationException("BANK_RESERVE_REVERSAL_NOT_IMPLEMENTED");
    }
}
