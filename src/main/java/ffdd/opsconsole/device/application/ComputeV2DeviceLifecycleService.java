package ffdd.opsconsole.device.application;

import ffdd.opsconsole.device.compute.v2.ComputeV2Runtime;
import ffdd.opsconsole.device.compute.v2.ComputeV2Runtime.RuntimeView;
import ffdd.opsconsole.shared.audit.AuditLogService;
import ffdd.opsconsole.shared.audit.AuditLogWriteRequest;
import ffdd.opsconsole.shared.canonical.mapper.CanonicalStateMapper;
import ffdd.opsconsole.shared.exception.BizException;
import ffdd.opsconsole.shared.outbox.EventOutboxService;
import java.util.*;
import java.util.concurrent.atomic.AtomicLong;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.env.Environment;
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
        if(!row.pendingDeactivate()) {
            if(mapper.markDevicePendingDeactivate(user,device,version)!=1)throw error("DEVICE_VERSION_CONFLICT");
            record("COMPUTE_V2_DEVICE_DEACTIVATE_PENDING",user,row,view.get().event().taskNo(),version);
        }
        var event=view.get().event();
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization(){
            @Override public void afterCommit(){
                try{drain(user,device,event.taskNo(),event.generation());}
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
        if(view.executorId()!=null)return false;
        var event=view.event();
        if(event.task().startedAt()==null)return "FAILED".equals(view.status());
        return runtime.orElseThrow().hasExited(event.taskNo(),event.generation());
    }

    private void drain(long user,long device,String task,long generation) {
        var current=ownedView(user,device);
        if(current.isEmpty() || !task.equals(current.get().event().taskNo()) || current.get().event().generation()!=generation)return;
        if(current.get().event().task().startedAt()==null && Set.of("ISSUING","QUEUED").contains(current.get().status()))
            runtime.orElseThrow().cancelQueued(user,device,generation,"USER_DEACTIVATION_PENDING");
        finalizeExited(user,device,task,generation);
    }

    /** Both successful exits and signed physical-exit recovery call this; repeated DB failures are retryable. */
    public boolean finalizeExited(long user,long device,String task,long generation) {
        if(runtime.isEmpty())return false;
        return Boolean.TRUE.equals(transaction.execute(status->{
            var owner=mapper.lockUser(user);
            var row=mapper.lockDeviceForUserCommand(device);
            if(owner==null || owner.sandbox() || row==null || row.userId()!=user || !row.pendingDeactivate()
                    || !"OWNED".equalsIgnoreCase(row.ownershipStatus()))return false;
            var current=ownedView(user,device);
            if(current.isPresent()){
                var view=current.get();
                if(!task.equals(view.event().taskNo()) || generation!=view.event().generation()
                        || !row.instanceNo().equals(view.event().instanceNo()) || !drained(view))return false;
            } else if(!mapper.hasComputeV2Exit(user,device,task,generation,scope))return false;
            if(mapper.hasActiveComputeV1Task(user,device) || mapper.hasUnresolvedComputeV1Request(user,device))return false;
            var attribution=mapper.userEventAttribution(user);
            if(attribution==null)throw error("USER_EVENT_ATTRIBUTION_UNAVAILABLE");
            if(mapper.deactivatePendingComputeV2Device(user,device,row.rowVersion())!=1)return false;
            mapper.markDeviceRuntimeDeactivated(device);
            var state=Map.<String,Object>of("userId",user,"deviceId",device,"instanceNo",row.instanceNo(),
                    "previousStatus",row.status(),"status","DEACTIVATED","rowVersion",row.rowVersion()+1,
                    "taskNo",task,"generation",generation);
            outbox.publishUserEvent("USER_DEVICE",row.instanceNo(),"device.deactivated",user,attribution.phase(),
                    attribution.accountAgeMonths(),attribution.cohort(),state);
            record("USER_DEVICE_DEFERRED_DEACTIVATED",user,row,task,row.rowVersion()+1);
            return true;
        }));
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
                if(current.isPresent())drain(row.userId(),row.deviceId(),current.get().event().taskNo(),current.get().event().generation());
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
