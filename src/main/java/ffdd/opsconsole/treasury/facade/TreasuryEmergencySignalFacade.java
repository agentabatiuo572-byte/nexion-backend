package ffdd.opsconsole.treasury.facade;

import ffdd.opsconsole.common.boundary.DomainFacade;
import java.math.BigDecimal;

public interface TreasuryEmergencySignalFacade extends DomainFacade {
    TreasuryEmergencySignalSnapshot snapshot();

    BigDecimal bankRunRedlinePct();

    /** Pure bank-run read for consumers that must not trigger D3 forecast promotion. */
    BankRunRead bankRunRead();

    record BankRunRead(BigDecimal ratioPct, BigDecimal reserveUsdt,
                       BigDecimal withdrawalRequests24hUsdt, BigDecimal redlinePct) {}
}
