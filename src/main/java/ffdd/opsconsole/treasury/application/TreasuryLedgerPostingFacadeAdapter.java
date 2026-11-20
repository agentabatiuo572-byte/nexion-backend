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
    private final org.springframework.beans.factory.ObjectProvider<ffdd.opsconsole.team.application.DirectReferralService> directReferrals;

    @Override
    public void releaseCommissionFunds(Long eventId) {
        var direct = directReferrals == null ? null : directReferrals.getIfAvailable();
        String group = direct == null ? null : direct.groupForEvent(eventId);
        if (group != null) { direct.releaseEvent(eventId); return; }
        if (direct != null) direct.lockEventSource(eventId, true);
        ledgerRepository.releaseCommissionFunds(eventId);
    }

    @Override
    public boolean reverseCommissionFunds(Long eventId) {
        var direct = directReferrals == null ? null : directReferrals.getIfAvailable();
        String group = direct == null ? null : direct.groupForEvent(eventId);
        if (group != null) { direct.reverseEvent(eventId); return true; }
        if (direct != null) direct.lockEventSource(eventId, false);
        return ledgerRepository.reverseCommissionFunds(eventId);
    }

    @Override
    public void postLedgerEntry(String bizNo, Long userId, String bizType, String asset, String direction,
                                BigDecimal amount, String status, String remark) {
        ledgerRepository.postLedgerEntry(bizNo, userId, bizType, asset, direction, amount, status, remark);
    }

    @Override
    public void postLedgerEntryWithBalanceAfter(String bizNo, Long userId, String bizType, String asset, String direction,
                                                BigDecimal amount, BigDecimal balanceAfter, String status, String remark) {
        ledgerRepository.postLedgerEntryWithBalanceAfter(bizNo, userId, bizType, asset, direction, amount, balanceAfter,
                status, remark);
    }

    @Override
    public void postLedgerEntryWithActualBalance(String bizNo, Long userId, String bizType, String asset,
                                                 String direction, BigDecimal amount, String status, String remark) {
        ledgerRepository.postLedgerEntryWithActualBalance(bizNo, userId, bizType, asset, direction, amount, status, remark);
    }

    @Override
    public void settleBankWithdrawalReserve(String withdrawalNo, BigDecimal amount, long providerId, LocalDateTime now) {
        ledgerRepository.settleBankWithdrawalReserve(withdrawalNo, amount, providerId, now);
    }

    @Override
    public void reverseLegacyBankWithdrawalReserve(String withdrawalNo, BigDecimal amount, LocalDateTime now) {
        ledgerRepository.reverseLegacyBankWithdrawalReserve(withdrawalNo, amount, now);
    }

    @Override
    public void reverseBep20WithdrawalReserve(String withdrawalNo, BigDecimal amount, LocalDateTime now) {
        ledgerRepository.reverseBep20WithdrawalReserve(withdrawalNo, amount, now);
    }
}
