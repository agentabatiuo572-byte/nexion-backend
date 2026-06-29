package ffdd.opsconsole.treasury.application;

import ffdd.opsconsole.treasury.domain.TreasuryLedgerRepository;
import ffdd.opsconsole.treasury.facade.TreasuryLedgerPostingFacade;
import java.math.BigDecimal;
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
}
