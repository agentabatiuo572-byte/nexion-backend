package ffdd.opsconsole.treasury.facade;

import ffdd.opsconsole.common.boundary.DomainFacade;
import java.math.BigDecimal;

public interface TreasuryCoverageFacade extends DomainFacade {
    TreasuryCoverageSnapshot snapshot();

    default TreasuryCoverageSnapshot snapshotAtNexPrice(BigDecimal proposedPrice) {
        return new TreasuryCoverageSnapshot(BigDecimal.ZERO, BigDecimal.ZERO, false);
    }
}
