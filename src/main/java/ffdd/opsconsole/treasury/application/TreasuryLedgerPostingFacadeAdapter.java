package ffdd.opsconsole.treasury.application;

import ffdd.opsconsole.treasury.domain.TreasuryLedgerRepository;
import ffdd.opsconsole.treasury.facade.TreasuryLedgerPostingFacade;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class TreasuryLedgerPostingFacadeAdapter implements TreasuryLedgerPostingFacade {
    private final TreasuryLedgerRepository ledgerRepository;

    @Override
    public void postLedgerEntry(String bizNo, Long userId, String bizType, String asset, String direction,
                                BigDecimal amount, String status, String remark) {
        ledgerRepository.postLedgerEntry(bizNo, userId, bizType, asset, direction, amount, status, remark);
    }

    @Override
    public void settleBankWithdrawalReserve(String withdrawalNo, BigDecimal amount, long providerId, LocalDateTime now) {
        ledgerRepository.settleBankWithdrawalReserve(withdrawalNo, amount, providerId, now);
    }

    @Override
    public void reverseLegacyBankWithdrawalReserve(String withdrawalNo, BigDecimal amount, LocalDateTime now) {
        ledgerRepository.reverseLegacyBankWithdrawalReserve(withdrawalNo, amount, now);
    }
}
