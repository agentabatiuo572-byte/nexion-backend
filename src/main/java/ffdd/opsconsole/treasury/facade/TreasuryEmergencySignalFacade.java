package ffdd.opsconsole.treasury.facade;

import ffdd.opsconsole.common.boundary.DomainFacade;
import java.math.BigDecimal;

public interface TreasuryEmergencySignalFacade extends DomainFacade {
    TreasuryEmergencySignalSnapshot snapshot();

    BigDecimal bankRunRedlinePct();
}
