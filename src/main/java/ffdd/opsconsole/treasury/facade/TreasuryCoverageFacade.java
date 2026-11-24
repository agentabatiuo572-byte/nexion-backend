package ffdd.opsconsole.treasury.facade;

import ffdd.opsconsole.common.boundary.DomainFacade;
import java.math.BigDecimal;

public interface TreasuryCoverageFacade extends DomainFacade {
    TreasuryCoverageSnapshot snapshot();

    /** Read committed coverage independently of a caller's pending financial writes. */
    default TreasuryCoverageSnapshot snapshotIsolated() {
        return snapshot();
    }

    default TreasuryCoverageSnapshot snapshotAtNexPrice(BigDecimal proposedPrice) {
        return new TreasuryCoverageSnapshot(BigDecimal.ZERO, BigDecimal.ZERO, false);
    }
}
