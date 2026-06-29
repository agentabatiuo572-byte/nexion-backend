package ffdd.opsconsole.treasury.facade;

import ffdd.opsconsole.common.boundary.DomainFacade;
import java.math.BigDecimal;

public interface TreasuryLedgerPostingFacade extends DomainFacade {
    void postLedgerEntry(String bizNo, Long userId, String bizType, String asset, String direction,
                         BigDecimal amount, String status, String remark);
}
