package ffdd.opsconsole.finance.facade;

import ffdd.opsconsole.common.boundary.DomainFacade;
import java.time.LocalDateTime;

/** Trusted AUTH producer only, after its actual INSERT token and outbox have been verified. */
public interface SupportFundAuthBirthFacade extends DomainFacade {
    Outcome record(AuthenticatedRegistrationFacts facts);
    enum Outcome { BOUND, UNKNOWN, EXCLUDED }
    // No user credentials, request fields, or caller-supplied wallet/journal facts.
    record AuthenticatedRegistrationFacts(long customerId, LocalDateTime customerCreatedAt,
        String expectedWalletIncarnation, String eventId, long outboxId, LocalDateTime eventTs, int schemaRevision) {}
}
