package ffdd.opsconsole.device.application;

import ffdd.opsconsole.device.mapper.SimulatedTaskMapper;
import ffdd.opsconsole.shared.audit.AuditLogService;
import ffdd.opsconsole.shared.audit.AuditLogWriteRequest;
import ffdd.opsconsole.shared.canonical.mapper.CanonicalStateMapper;
import ffdd.opsconsole.shared.exception.BizException;
import ffdd.opsconsole.shared.outbox.EventOutboxService;
import java.util.Map;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Joins the task transaction: a closed simulated interval can release a pending device slot. */
@Service
@RequiredArgsConstructor
public class SimulatedTaskLifecycleService {
    private final SimulatedTaskMapper simulated;
    private final CanonicalStateMapper canonical;
    private final AuditLogService audit;
    private final EventOutboxService outbox;

    @Transactional
    public boolean finalizeStopped(long userId,long deviceId) {
        var owner=canonical.lockUser(userId);
        var device=canonical.lockDeviceForUserCommand(deviceId);
        if(owner==null || owner.sandbox() || device==null || device.userId()!=userId
                || !device.pendingDeactivate() || !"OWNED".equalsIgnoreCase(device.ownershipStatus()))return false;
        var runtime=simulated.lockRuntime(deviceId);
        if(runtime==null || runtime.userId()!=userId || !runtime.instanceNo().equals(device.instanceNo())
                || !Set.of("STOPPED","EXPIRED").contains(runtime.state())
                || runtime.currentTaskNo()!=null || runtime.nextActionAt()!=null)return false;
        if(simulated.unresolvedLegacy(userId,deviceId)!=0 || simulated.unresolvedV2(userId,deviceId)!=0)return false;
        var attribution=canonical.userEventAttribution(userId);
        if(attribution==null)throw new BizException(409,"USER_EVENT_ATTRIBUTION_UNAVAILABLE");
        if(canonical.deactivatePendingComputeV2Device(userId,deviceId,device.rowVersion())!=1)return false;
        canonical.markDeviceRuntimeDeactivated(deviceId);
        var state=Map.<String,Object>of("userId",userId,"deviceId",deviceId,"instanceNo",device.instanceNo(),
                "previousStatus",device.status(),"status","DEACTIVATED","rowVersion",device.rowVersion()+1,
                "generation",runtime.generation(),"executionMode","SIMULATED");
        outbox.publishUserEvent("USER_DEVICE",device.instanceNo(),"device.deactivated",userId,attribution.phase(),
                attribution.accountAgeMonths(),attribution.cohort(),Map.of("userId",userId,"deviceId",deviceId,
                    "instanceNo",device.instanceNo(),"previousStatus",device.status(),"status","DEACTIVATED",
                    "rowVersion",device.rowVersion()+1));
        audit.recordRequiredForTrustedActor(AuditLogWriteRequest.builder().action("USER_DEVICE_DEFERRED_DEACTIVATED")
                .resourceType("USER_DEVICE").resourceId(Long.toString(deviceId)).bizNo(device.instanceNo()).userId(userId)
                .actorId(0L).actorType("SYSTEM").actorUsername("system").method("INTERNAL")
                .path("/internal/simulated-tasks/device-lifecycle").result("SUCCESS").riskLevel("MEDIUM").detail(state).build());
        return true;
    }
}
