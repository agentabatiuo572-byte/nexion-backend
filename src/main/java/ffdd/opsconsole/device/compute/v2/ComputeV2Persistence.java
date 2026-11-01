package ffdd.opsconsole.device.compute.v2;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/** A successful return is a committed SQL checkpoint, never merely a Redis or MQ acknowledgement. */
public interface ComputeV2Persistence {
    PersistenceResult persistBatch(SealedBatch batch);
    Optional<PersistenceResult> findCommitted(String batchId);
    List<ComputeV2Event> recover(String deploymentScope);

    /** Cold retry reads the original frozen issuance without publishing or applying it again. */
    default Optional<ComputeV2Event> findIssued(long userId,long deviceId,String requestId) { return Optional.empty(); }
    default Optional<ComputeV2Event> findLifecycle(String eventId) { return Optional.empty(); }

    record SealedBatch(String batchId, Instant windowStart, Instant windowEnd, List<ComputeV2Event> events) {
        public SealedBatch {
            if (batchId == null || batchId.isBlank() || batchId.length() > 192 || windowStart == null
                    || windowEnd == null || windowStart.getNano() != 0 || windowEnd.getNano() != 0
                    || !windowStart.plusSeconds(60).equals(windowEnd)
                    || events == null || events.isEmpty() || events.size() > 200)
                throw new IllegalArgumentException("Explicit immutable 60-second window and 1..200 events required");
            events = List.copyOf(events);
        }
    }

    record PersistenceResult(String batchId, List<String> eventIds, List<String> allocationIds,
            Instant postedAt, boolean replayed) {
        public PersistenceResult { eventIds = List.copyOf(eventIds); allocationIds = List.copyOf(allocationIds); }
    }
}
