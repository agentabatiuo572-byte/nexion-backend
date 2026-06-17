package ffdd.opsconsole.treasury.facade;

import ffdd.opsconsole.common.boundary.DomainFacade;

public interface TreasuryCoverageFacade extends DomainFacade {
    TreasuryCoverageSnapshot snapshot();
}
