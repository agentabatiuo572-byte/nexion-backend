package ffdd.opsconsole.treasury.facade;

import ffdd.opsconsole.common.boundary.DomainFacade;

/** Captures this cash write; historical statistical eligibility never authorizes the write. */
public interface SupportCommissionCaptureFacade extends DomainFacade {
    Before begin(long customerId,long eventId,String phase);
    void recorded(Before before);
    interface Before {}
}
