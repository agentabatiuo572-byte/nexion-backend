package ffdd.opsconsole.content.facade;

import ffdd.opsconsole.common.boundary.DomainFacade;
import java.util.List;
import java.util.Optional;

/** Read-only facade for L5; callers cannot mutate I5 disclosure lifecycle state. */
public interface RegulatoryDisclosureFacade extends DomainFacade {
    List<RegulatoryDisclosureSnapshot> currentOptions();

    Optional<RegulatoryDisclosureSnapshot> resolveCurrent(String jurisdictionCode, String disclosureVersion);
}
