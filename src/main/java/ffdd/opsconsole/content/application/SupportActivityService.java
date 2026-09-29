package ffdd.opsconsole.content.application;

import ffdd.opsconsole.common.boundary.ApplicationService;
import ffdd.opsconsole.content.mapper.SupportMaintenanceMapper;
import ffdd.opsconsole.shared.exception.BizException;
import java.time.LocalDateTime;
import lombok.RequiredArgsConstructor;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@ApplicationService
@RequiredArgsConstructor
public class SupportActivityService {
    private final SupportMaintenanceMapper mapper;
    private final SupportOwnershipService ownership;
    private final SupportMaintenanceService maintenance;
    private final ProductionSupportPathGuard productionPathGuard;

    public record Coverage(LocalDateTime coverageStartAt, LocalDateTime observedThroughAt) {
        public boolean covers(LocalDateTime from,LocalDateTime through) {
            return !coverageStartAt.isAfter(from) && !observedThroughAt.isBefore(through);
        }
    }

    /** Only validated interactive login may call this; no timestamp or event kind comes from a client. */
    @Transactional(propagation=Propagation.MANDATORY)
    public void interactiveLogin(Long customer,String sessionChainId) {
        productionPathGuard.requireAllowed(customer);
        if(sessionChainId==null || !sessionChainId.matches("[a-f0-9-]{36}"))
            throw new IllegalArgumentException("SUPPORT_ACTIVITY_SESSION_REQUIRED");
        ownership.lockCustomer(customer);
        if(mapper.captureFence()==null) throw new BizException(503,"SUPPORT_ACTIVITY_CAPTURE_UNAVAILABLE");
        String source="INTERACTIVE_LOGIN:"+sessionChainId;
        var previous=mapper.activityEvent(source);
        if(previous!=null) {
            if(!previous.customerId().equals(customer)) throw new BizException(409,"SUPPORT_ACTIVITY_SOURCE_CONFLICT");
            return; // Replaying an old event never gets another chance to complete a newer cycle.
        }
        mapper.initializeActivity(customer);
        var state=mapper.activity(customer);
        long seq=Math.addExact(state.activitySeq(),1);
        LocalDateTime at=mapper.eventTime();
        if(state.lastEffectiveAt()!=null && at.isBefore(state.lastEffectiveAt()))
            throw new BizException(503,"SUPPORT_ACTIVITY_CLOCK_REGRESSED");
        mapper.insertActivity(customer,seq,source,at);
        mapper.updateActivity(customer,seq,at);
        maintenance.effectiveActivity(mapper.activityEvent(source));
    }

    /**
     * Only takes the global fence, never customer/assignment locks. Call before opening the read snapshot.
     * All successful pre-fence logins have committed their activity. Blocked/new logins receive time > watermark.
     * No queue means downtime cannot lose a successful login; DB/capture failure rolls back login too.
     * ponytail: one global checkpoint fence; shard only if measured checkpoint contention warrants it.
     */
    @Transactional(propagation=Propagation.REQUIRES_NEW)
    public Coverage checkpoint() {
        productionPathGuard.requireOpsWriteAllowed();
        if(mapper.checkpointFence()==null) throw new BizException(503,"SUPPORT_ACTIVITY_CAPTURE_UNAVAILABLE");
        if(mapper.advanceWatermark()!=1) throw new BizException(503,"SUPPORT_ACTIVITY_CAPTURE_UNAVAILABLE");
        return mapper.checkpointFence();
    }
}
