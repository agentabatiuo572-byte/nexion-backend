package ffdd.opsconsole.finance.facade;

import ffdd.opsconsole.common.boundary.DomainFacade;
import java.util.Collection;

/** Raw transactional journal binding only; this is neither wallet authorization nor business READY. */
public interface SupportFundJournalFacade extends DomainFacade {
    Before begin(Collection<Long> customerIds);
    /** Registration writer only: binds revision zero as a raw candidate, never proves account birth. */
    Before beginBirth(long customerId);
    BoundReceipt bind(Before before, ReceiptInput input);

    /** Only the private service-issued implementation is accepted, once, in its issuing transaction. */
    interface Before {}

    /** JSON strings are immutable; events must decode as SupportFundAllocation.Event records. */
    record ReceiptInput(String operationKey, String businessType, String phase, String sourceTable,
                        String sourceKey, String sourceEnvironment, String orderedEventsJson,
                        String sourceSnapshotJson, String sourceProofJson) {}

    record BoundReceipt(long operationId, String operationKey, String receiptSha256, int journalLegs) {}
}
