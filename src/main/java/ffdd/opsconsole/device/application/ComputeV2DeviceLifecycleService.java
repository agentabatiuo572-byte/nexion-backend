package ffdd.opsconsole.device.application;

import ffdd.opsconsole.device.compute.v2.ComputeV2Runtime;
import ffdd.opsconsole.device.compute.v2.ComputeV2Runtime.RuntimeView;
import ffdd.opsconsole.device.compute.v2.ComputeV2Event;
import ffdd.opsconsole.device.mapper.DeviceSlotReplacementMapper;
import ffdd.opsconsole.device.mapper.SimulatedTaskMapper.RuntimeRow;
import ffdd.opsconsole.shared.audit.AuditLogService;
import ffdd.opsconsole.shared.audit.AuditLogWriteRequest;
import ffdd.opsconsole.shared.canonical.mapper.CanonicalStateMapper;
import ffdd.opsconsole.shared.exception.BizException;
import ffdd.opsconsole.shared.outbox.EventOutboxService;
import java.util.*;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BooleanSupplier;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.env.Environment;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.*;

/** Normal lifecycle commands drain Redis execution without deleting old SQL/UNKNOWN evidence. */
@Service @Slf4j
public class ComputeV2DeviceLifecycleService {
    private final Optional<ComputeV2Runtime> runtime;
    private final CanonicalStateMapper mapper;
    private final AuditLogService audit;
    private final EventOutboxService outbox;
    private final TransactionTemplate transaction;
    private final Set<Long> enrolled;
    private final SimulatedTaskService simulated;
    private final SimulatedTaskLifecycleService simulatedLifecycle;
    @org.springframework.beans.factory.annotation.Autowired
    private DeviceSlotReplacementCompletion slotReplacement;
    @org.springframework.beans.factory.annotation.Autowired
    private DeviceSlotReplacementMapper slotReplacements;
    @SuppressWarnings("ArchitectureConfigField") // Derived from the constructor-injected environment.
    private final String scope;
    private final AtomicLong cursor=new AtomicLong();

    public ComputeV2DeviceLifecycleService(Optional<ComputeV2Runtime> runtime,CanonicalStateMapper mapper,
            PlatformTransactionManager transactions,AuditLogService audit,EventOutboxService outbox,Environment environment,
            SimulatedTaskService simulated,SimulatedTaskLifecycleService simulatedLifecycle) {
        this.runtime=runtime;this.mapper=mapper;this.audit=audit;this.outbox=outbox;
        this.simulated=simulated;this.simulatedLifecycle=simulatedLifecycle;
        transaction=new TransactionTemplate(transactions);
        transaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        scope=environment.getProperty("nexion.compute-v2.deployment-scope","");
        var devices=new HashSet<Long>();
        for(String id:environment.getProperty("nexion.compute-v2.device-ids","").split(","))if(!id.isBlank())devices.add(Long.parseLong(id.trim()));
        enrolled=Set.copyOf(devices);
    }

    /** Caller owns its existing user/device command transaction; cancellation runs only after commit. */
    public boolean manages(long device){return simulated.manages(device) || runtime.isPresent() && enrolled.contains(device);}

    public void cancelSimulatedSlotStop(long userId, long deviceId) {
        if (simulated.manages(deviceId)) simulated.cancelPendingSlotStop(userId, deviceId);
    }

    public Boolean slotReplacementWaitsForSimulated(long userId,long deviceId,String instanceNo) {
        return simulated.slotReplacementWaits(userId,deviceId,instanceNo);
    }

    /** Read-only estimate for Compute V2. The caller also checks simulated and legacy work. */
    public boolean slotReplacementWaitsForComputeV2(long userId,long deviceId,String instanceNo) {
        if(runtime.isEmpty() || !enrolled.contains(deviceId))return false;
        var snapshot=runtime.get().replacementView(userId,deviceId);
        if(snapshot.isPresent()) {
            var view=snapshot.get();
            return slotReplacementWaits(view.event(),view.status(),view.executing(),instanceNo,view::exited)
                    || mapper.hasUnresolvedComputeV1Request(userId,deviceId);
        }
        if(mapper.hasUnresolvedComputeV1Request(userId,deviceId))
            throw error("COMPUTE_V2_LEGACY_REQUEST_UNRESOLVED");
        return false;
    }

    public boolean deferDeactivation(long user,long device,Long expectedVersion) {
        if(simulated.manages(device) && (simulated.runtimesByUser(user).containsKey(device)
                || !simulated.hasLegacyPending(user,device))) {
            stopSimulated(user,device,expectedVersion,true);return true;
        }
        var view=ownedView(user,device);
        if(view.isEmpty()){
            if(runtime.isPresent() && enrolled.contains(device) && mapper.hasUnresolvedComputeV1Request(user,device))
                throw error("COMPUTE_V2_LEGACY_REQUEST_UNRESOLVED");
            return false;
        }
        if(!TransactionSynchronizationManager.isActualTransactionActive() || !TransactionSynchronizationManager.isSynchronizationActive())
            throw new IllegalStateException("COMPUTE_V2_LIFECYCLE_TRANSACTION_REQUIRED");
        var owner=mapper.lockUser(user);
        var row=mapper.lockDeviceForUserCommand(device);
        if(owner==null || owner.sandbox() || row==null || row.userId()!=user || !"OWNED".equalsIgnoreCase(row.ownershipStatus())
                || !row.instanceNo().equals(view.get().event().instanceNo()))throw error("COMPUTE_V2_OWNER_CHANGED");
        if(row.rowVersion()==null)throw error("DEVICE_VERSION_CONFLICT");
        long version=expectedVersion==null?row.rowVersion():expectedVersion;
        if(row.rowVersion()!=version)throw error("DEVICE_VERSION_CONFLICT");
        boolean immediateReplacement=slotReplacements.successor(user,device)!=null
                && !slotReplacementWaits(view.get(),row.instanceNo())
                && !mapper.hasActiveTask(user,device) && !mapper.hasUnresolvedComputeV1Request(user,device);
        var event=view.get().event();
        if(!row.pendingDeactivate()) {
            if(mapper.markDevicePendingDeactivate(user,device,version)!=1)throw error("DEVICE_VERSION_CONFLICT");
            if(!immediateReplacement)record("COMPUTE_V2_DEVICE_DEACTIVATE_PENDING",user,row,event.taskNo(),version);
        }
        if(immediateReplacement) {
            // Dispatch takes the same device lock before acquiring a Redis permit. Clean up the queue only after commit.
            if(!finishLocked(user,mapper.lockDeviceForUserCommand(device),event.taskNo(),"USER_DEVICE_DEACTIVATED"))
                throw error("DEVICE_VERSION_CONFLICT");
            if(!neverStartedQueue(view.get()))return true;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization(){
            @Override public void afterCommit(){
                try{drain(user,device,event.taskNo(),event.generation(),immediateReplacement?version+1:null);}
                catch(RuntimeException failure){log.warn("Compute v2 pending deactivation retained device={} type={}",device,failure.getClass().getSimpleName());}
            }
        });
        return true;
    }

    /** Trade-in/unbind never recycle a running or uncertain generation underneath its proof. */
    public void requireDrainedForReplacement(long user,long device) {
        if(simulated.manages(device)) {
            var current=simulated.runtimesByUser(user).get(device);
            if(current!=null && (current.currentTaskNo()!=null || current.nextActionAt()!=null
                    || !Set.of("STOPPED","EXPIRED").contains(current.state())))
                throw error("SIMULATED_DEVICE_BUSY");
            if(current!=null) {
                if(simulated.hasLegacyPending(user,device))throw error("COMPUTE_V2_LEGACY_REQUEST_UNRESOLVED");
                return;
            }
        }
        var view=ownedView(user,device);
        if(runtime.isPresent() && enrolled.contains(device) && mapper.hasUnresolvedComputeV1Request(user,device))
            throw error("COMPUTE_V2_LEGACY_REQUEST_UNRESOLVED");
        if(view.isPresent() && (!drained(view.get()) || mapper.hasUnresolvedComputeV1Request(user,device)))
            throw error("COMPUTE_V2_DEVICE_BUSY");
    }

    /** Checks the source without writes. The App already holds the user and device locks. */
    RuntimeRow lockAppReplacementRuntime(long user, long device, String instanceNo, boolean activated) {
        var view = ownedView(user, device);
        if (view.isPresent()) {
            var event = view.get().event();
            if (!instanceNo.equals(event.instanceNo()) || !scope.equals(event.deploymentScope())) {
                throw error("COMPUTE_V2_OWNER_CHANGED");
            }
        }
        if (mapper.hasUnresolvedComputeV1Request(user, device)) {
            throw error("COMPUTE_V2_LEGACY_REQUEST_UNRESOLVED");
        }
        if (view.isPresent() && !drained(view.get())) {
            throw error("COMPUTE_V2_DEVICE_BUSY");
        }
        return simulated.lockReplacementRuntime(user, device, instanceNo, activated,
                runtime.isPresent() && enrolled.contains(device));
    }

    void cancelSimulatedForReplacement(RuntimeRow lockedRuntime) {
        simulated.cancelForReplacement(lockedRuntime);
    }

    /** Called under the account and device locks before the H5 sign-in can release the phone slot. */
    public void stopForH5Login(long userId, long deviceId, String instanceNo) {
        var runtimeToStop = lockAppReplacementRuntime(userId, deviceId, instanceNo, true);
        if (runtimeToStop != null) simulated.stopForH5Login(runtimeToStop);
    }

    /** An uncertain runtime read leaves the swap pending after a legacy task exits. */
    public boolean hasOtherWorkBeforePendingDeactivation(long userId, long deviceId, String instanceNo) {
        boolean enrolledInV2 = runtime.isPresent() && enrolled.contains(deviceId);
        if (enrolledInV2) {
            try {
                var snapshot = runtime.orElseThrow().replacementView(userId, deviceId);
                if (snapshot.isPresent()) {
                    var view = snapshot.get();
                    var event = view.event();
                    if (!instanceNo.equals(event.instanceNo()) || !scope.equals(event.deploymentScope())
                            || !drained(view.status(), event.task().startedAt() != null, view.executing(), view::exited))
                        return true;
                }
            } catch (DataAccessException | BizException uncertain) {
                return true;
            }
        }
        if (mapper.hasUnresolvedComputeV1Request(userId, deviceId)) return true;
        try {
            var simulatedRuntime = simulated.lockReplacementRuntime(userId, deviceId, instanceNo, true, enrolledInV2);
            return simulatedRuntime != null && simulatedRuntime.currentTaskNo() != null;
        } catch (BizException blocked) {
            return switch (blocked.getMessage()) {
                case "SIMULATED_DEVICE_BUSY", "COMPUTE_V2_DEVICE_BUSY", "COMPUTE_V2_LEGACY_REQUEST_UNRESOLVED" -> true;
                default -> throw blocked;
            };
        }
    }

    private Optional<RuntimeView> ownedView(long user,long device) {
        if(runtime.isEmpty() || !enrolled.contains(device))return Optional.empty();
        if(!runtime.get().isReady())throw new BizException(503,"COMPUTE_V2_RUNTIME_RECOVERING");
        var view=runtime.get().viewDevice(device);
        if(view.isPresent() && (view.get().event().userId()!=user || view.get().event().deviceId()!=device))
            throw error("COMPUTE_V2_OWNER_CHANGED");
        return view;
    }

    public ffdd.opsconsole.device.mapper.SimulatedTaskMapper.RuntimeRow stopSimulated(
            long user,long device,Long expectedVersion,boolean afterCurrent) {
        if(!simulated.manages(device))throw error("SIMULATED_TASKS_UNAVAILABLE");
        if(!TransactionSynchronizationManager.isActualTransactionActive())
            throw new IllegalStateException("SIMULATED_LIFECYCLE_TRANSACTION_REQUIRED");
        var owner=mapper.lockUser(user);
        var row=mapper.lockDeviceForUserCommand(device);
        if(owner==null || owner.sandbox() || row==null || row.userId()!=user
                || !"OWNED".equalsIgnoreCase(row.ownershipStatus()))throw error("DEVICE_FORBIDDEN");
        if(row.rowVersion()==null || expectedVersion!=null && !expectedVersion.equals(row.rowVersion()))
            throw error("DEVICE_VERSION_CONFLICT");
        if(!row.pendingDeactivate()) {
            if(mapper.markDevicePendingDeactivate(user,device,row.rowVersion())!=1)throw error("DEVICE_VERSION_CONFLICT");
            record("SIMULATED_DEVICE_DEACTIVATE_PENDING",user,row,"SIMULATED",row.rowVersion());
        }
        var state=simulated.requestStop(user,device,afterCurrent);
        simulatedLifecycle.finalizeStopped(user,device);
        return state;
    }

    private boolean drained(RuntimeView view) {
        var event=view.event();
        return drained(view.status(),event.task().startedAt()!=null,view.executorId()!=null,
                ()->runtime.orElseThrow().hasExited(event.taskNo(),event.generation()));
    }

    private boolean drained(String status,boolean started,boolean executing,BooleanSupplier exited) {
        return !executing && (started ? exited.getAsBoolean() : "FAILED".equals(status));
    }

    private boolean slotReplacementWaits(RuntimeView view,String instanceNo) {
        var event=view.event();
        return slotReplacementWaits(event,view.status(),view.executorId()!=null,instanceNo,
                ()->runtime.orElseThrow().hasExited(event.taskNo(),event.generation()));
    }

    private boolean slotReplacementWaits(ComputeV2Event event,String status,boolean executing,String instanceNo,BooleanSupplier exited) {
        if(!instanceNo.equals(event.instanceNo()) || !scope.equals(event.deploymentScope()))
            throw error("COMPUTE_V2_OWNER_CHANGED");
        if(status==null)throw new BizException(503,"COMPUTE_V2_RUNTIME_RECOVERING");
        boolean started=event.task().startedAt()!=null;
        return switch(status) {
            case "ISSUING","QUEUED" -> executing || started;
            case "COMPLETED","FAILED","RECOVERING" -> !drained(status,started,executing,exited);
            case "STARTING","COMPUTING","VERIFYING","FAILING" -> true;
            default -> throw new BizException(503,"COMPUTE_V2_RUNTIME_RECOVERING");
        };
    }

    private boolean neverStartedQueue(RuntimeView view) {
        return view.executorId()==null && view.event().task().startedAt()==null
                && Set.of("ISSUING","QUEUED").contains(view.status());
    }

    private void drain(long user,long device,String task,long generation,Long completedVersion) {
        var current=ownedView(user,device);
        if(current.isEmpty() || !task.equals(current.get().event().taskNo()) || current.get().event().generation()!=generation)return;
        if(neverStartedQueue(current.get()))cancelQueueWithDeviceLock(current.get(),completedVersion,false);
        finalizeExited(user,device,task,generation);
    }

    /** Retries queue cleanup when a synchronous device deactivation committed before its callback ran. */
    boolean discardStoppedQueue(RuntimeView view) {
        return (neverStartedQueue(view) || failedBeforeStart(view))
                && cancelQueueWithDeviceLock(view,null,true);
    }

    private boolean failedBeforeStart(RuntimeView view) {
        return "FAILED".equals(view.status()) && view.executorId()==null && view.event().task().startedAt()==null;
    }

    private boolean cancelQueueWithDeviceLock(RuntimeView view,Long completedVersion,boolean stoppedOnly) {
        var event=view.event();
        long user=event.userId(),device=event.deviceId();
        if(!scope.equals(event.deploymentScope()))return false;
        var runtimeService=runtime.orElseThrow();
        var cleanup=transaction.execute(status->{
            var owner=mapper.lockUser(user);
            var row=mapper.lockDeviceForUserCommand(device);
            if(owner==null || owner.sandbox() || row==null || row.userId()!=user
                    || !row.instanceNo().equals(event.instanceNo()))return new QueueCleanup(false,null);
            boolean owned="OWNED".equals(row.ownershipStatus());
            boolean deactivated="DEACTIVATED".equals(row.status()) && row.deactivatedAt()!=null;
            boolean stoppedWhileOwned=owned && deactivated && row.activatedAt()==null;
            boolean authorized=stoppedOnly
                    ? stoppedWhileOwned || deactivated && "REFUNDED".equals(row.ownershipStatus())
                    : owned && (row.pendingDeactivate() || stoppedWhileOwned && completedVersion!=null
                            && completedVersion.equals(row.rowVersion()));
            if(!authorized)return new QueueCleanup(false,null);
            if(stoppedOnly && failedBeforeStart(view)) {
                var current=ownedView(user,device);
                if(current.isPresent() && failedBeforeStart(current.get())
                        && event.taskNo().equals(current.get().event().taskNo())
                        && event.generation()==current.get().event().generation()) {
                    runtimeService.retire(user,device);
                    return new QueueCleanup(true,null);
                }
                return new QueueCleanup(false,null);
            }
            var reserved=runtimeService.reserveQueuedCancellation(user,device,event.generation(),"USER_DEACTIVATION_PENDING");
            return new QueueCleanup(reserved.isPresent(),reserved.orElse(null));
        });
        if(cleanup.event()!=null)runtimeService.confirmQueuedCancellation(cleanup.event());
        return cleanup.handled();
    }

    private record QueueCleanup(boolean handled,ComputeV2Event event) {}

    /** Both successful exits and signed physical-exit recovery call this; repeated DB failures are retryable. */
    public boolean finalizeExited(long user,long device,String task,long generation) {
        if(runtime.isEmpty())return false;
        return Boolean.TRUE.equals(transaction.execute(status->{
            var owner=mapper.lockUser(user);
            var row=mapper.lockDeviceForUserCommand(device);
            if(owner==null || owner.sandbox() || row==null || row.userId()!=user || !row.pendingDeactivate()
                    || !"OWNED".equalsIgnoreCase(row.ownershipStatus()))return false;
            var current=ownedView(user,device);
            boolean expiredReplacement=false;
            if(current.isPresent()){
                var view=current.get();
                if(!task.equals(view.event().taskNo()) || generation!=view.event().generation()
                        || !row.instanceNo().equals(view.event().instanceNo()))return false;
                if(!drained(view)) {
                    expiredReplacement=scope.equals(view.event().deploymentScope())
                            && slotReplacements.successor(user,device)!=null
                            && runtime.orElseThrow().hasExpiredTerminalResult(view);
                    if(!expiredReplacement)return false;
                }
            } else if(!mapper.hasComputeV2Exit(user,device,task,generation,scope))return false;
            if(mapper.hasActiveComputeV1Task(user,device) || mapper.hasUnresolvedComputeV1Request(user,device)
                    || expiredReplacement && mapper.hasActiveTask(user,device))return false;
            return finishLocked(user,row,task,expiredReplacement?"COMPUTE_V2_SLOT_REPLACED_AFTER_LEASE_EXPIRY"
                    :"USER_DEVICE_DEFERRED_DEACTIVATED");
        }));
    }

    private void finalizePendingPhoneTaskCancellation(long userId, long deviceId) {
        transaction.executeWithoutResult(status -> {
            var owner = mapper.lockUser(userId);
            var device = mapper.lockDeviceForUserCommand(deviceId);
            if (owner == null || owner.sandbox() || device == null || device.userId() != userId
                    || !device.pendingDeactivate() || !"OWNED".equals(device.ownershipStatus())) return;
            String taskNo = mapper.cancelledPendingSlotPhoneTask(userId, deviceId);
            if (taskNo == null || mapper.hasActiveTask(userId, deviceId)
                    || hasOtherWorkBeforePendingDeactivation(userId, deviceId, device.instanceNo())) return;
            finishLocked(userId, device, taskNo, "USER_DEVICE_DEFERRED_DEACTIVATED");
        });
    }

    private boolean finishLocked(long user,CanonicalStateMapper.UserDeviceCommandRow row,String task,String action) {
        var attribution=mapper.userEventAttribution(user);
        if(attribution==null)throw error("USER_EVENT_ATTRIBUTION_UNAVAILABLE");
        long device=row.id();
        if(mapper.deactivatePendingComputeV2Device(user,device,row.rowVersion())!=1)return false;
        mapper.markDeviceRuntimeDeactivated(device);
        slotReplacement.afterDeactivation(user,device);
        outbox.publishUserEvent("USER_DEVICE",row.instanceNo(),"device.deactivated",user,attribution.phase(),
                attribution.accountAgeMonths(),attribution.cohort(),Map.of("userId",user,"deviceId",device,
                    "instanceNo",row.instanceNo(),"previousStatus",row.status(),"status","DEACTIVATED",
                    "rowVersion",row.rowVersion()+1));
        record(action,user,row,task,row.rowVersion()+1);
        return true;
    }

    /** Called by the existing bounded dispatch tick; no new per-account scheduler or executor. */
    public synchronized void finalizePendingEnrolled() {
        if(runtime.isEmpty() || !runtime.get().isReady())return;
        var pending=mapper.pendingComputeV2Deactivations(cursor.get(),12);
        if(pending.isEmpty()){cursor.set(0);return;}
        for(var row:pending){
            cursor.set(row.deviceId());
            try{
                var current=ownedView(row.userId(),row.deviceId());
                if(current.isPresent())drain(row.userId(),row.deviceId(),current.get().event().taskNo(),current.get().event().generation(),null);
                else if(enrolled.contains(row.deviceId()) && mapper.cancelledPendingSlotPhoneTask(row.userId(),row.deviceId())!=null)
                    finalizePendingPhoneTaskCancellation(row.userId(),row.deviceId());
                else{
                    var exited=mapper.lastComputeV2Exit(row.userId(),row.deviceId(),scope);
                    if(exited!=null)finalizeExited(row.userId(),row.deviceId(),exited.taskNo(),exited.generation());
                }
            }catch(RuntimeException failure){log.warn("Compute v2 deactivation retry retained device={} type={}",row.deviceId(),failure.getClass().getSimpleName());}
        }
    }

    private void record(String action,long user,CanonicalStateMapper.UserDeviceCommandRow device,String task,long version) {
        audit.recordRequiredForTrustedActor(AuditLogWriteRequest.builder().action(action).resourceType("USER_DEVICE")
                .resourceId(Long.toString(device.id())).bizNo(device.instanceNo()).userId(user).actorId(0L)
                .actorType("SYSTEM").actorUsername("system").method("INTERNAL").path("/internal/compute-v2/device-lifecycle")
                .result("SUCCESS").riskLevel("MEDIUM").detail(Map.of("deviceId",device.id(),"taskNo",task,"rowVersion",version)).build());
    }
    private BizException error(String code){return new BizException(409,code);}
}
