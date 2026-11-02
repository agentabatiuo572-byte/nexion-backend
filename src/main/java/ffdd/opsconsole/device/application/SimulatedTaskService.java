package ffdd.opsconsole.device.application;

import ffdd.opsconsole.device.mapper.AppTaskAssignmentMapper;
import ffdd.opsconsole.device.mapper.SimulatedTaskMapper;
import ffdd.opsconsole.device.mapper.SimulatedTaskMapper.*;
import ffdd.opsconsole.shared.capacity.E3DeviceCapacityPolicy;
import ffdd.opsconsole.shared.config.DateTimeFormatConfig;
import ffdd.opsconsole.shared.exception.BizException;
import java.math.BigDecimal;
import java.security.SecureRandom;
import java.time.*;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.concurrent.atomic.AtomicLong;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.env.Environment;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** One shared SQL due loop. Reads never issue work, extend a phone interval, or credit money. */
@Service
public class SimulatedTaskService {
    private static final Logger LOG=LoggerFactory.getLogger(SimulatedTaskService.class);
    private static final int BATCH_SIZE=500,CATCH_UP_LIMIT=12;
    private final SimulatedTaskMapper mapper;
    private final AppTaskAssignmentMapper assignments;
    private final SimulatedTaskPolicyService policies;
    private final SimulatedTaskSettlementService settlement;
    private final SimulatedTaskLifecycleService lifecycle;
    private final TransactionTemplate transaction;
    private final Environment environment;
    private final Clock clock;
    private final SecureRandom random=new SecureRandom();
    private final AtomicLong initialCursor=new AtomicLong();

    public SimulatedTaskService(SimulatedTaskMapper mapper,AppTaskAssignmentMapper assignments,
            SimulatedTaskPolicyService policies,SimulatedTaskSettlementService settlement,SimulatedTaskLifecycleService lifecycle,
            PlatformTransactionManager transactions,Environment environment,Clock clock) {
        this.mapper=mapper;this.assignments=assignments;this.policies=policies;this.settlement=settlement;this.lifecycle=lifecycle;
        this.transaction=new TransactionTemplate(transactions);this.environment=environment;this.clock=clock;
    }
    public record PhoneOnlineResult(boolean onlineConfirmed,int retryAfterSeconds,RuntimeRow runtime) { }
    public boolean enabled(){return environment.getProperty("nexion.simulated-tasks.enabled",Boolean.class,false);}
    public boolean manages(long deviceId){return enabled() && mapper.subject(deviceId)!=null;}
    public boolean hasLegacyPending(long userId,long deviceId) {
        return mapper.unresolvedLegacy(userId,deviceId)!=0 || mapper.unresolvedV2(userId,deviceId)!=0;
    }
    public Map<Long,TaskRow> currentTasksByUser(long userId) {
        if(!enabled())return Map.of();
        var result=new LinkedHashMap<Long,TaskRow>();
        for(var task:mapper.currentTasks(userId))result.put(task.deviceId(),task);
        return Map.copyOf(result);
    }
    public Map<Long,RuntimeRow> runtimesByUser(long userId) {
        if(!enabled())return Map.of();
        var result=new LinkedHashMap<Long,RuntimeRow>();
        for(var row:mapper.runtimesByUser(userId))result.put(row.deviceId(),row);
        return Map.copyOf(result);
    }

    /** The caller has already authenticated the current native installation and its own phone. */
    public PhoneOnlineResult phoneOnline(long userId,long deviceId) {
        requireEnabled();LocalDateTime received=now();
        return transaction.execute(status->{
            var device=owned(userId,deviceId);
            if(!phone(device))throw error(403,"SIMULATED_PHONE_REQUIRED");
            requireActive(device);
            if(device.pendingDeactivate())throw error(409,"SIMULATED_DEACTIVATION_PENDING");
            var runtime=mapper.lockRuntime(deviceId);
            if(runtime!=null) {
                requireIdentity(device,runtime);
                if(runtime.phoneValidUntil()!=null && !received.isBefore(runtime.phoneValidUntil())) {
                    runtime=advance(device,runtime,received);
                    if(runtime.currentTaskNo()!=null || runtime.nextActionAt()!=null)
                        return new PhoneOnlineResult(false,30,runtime);
                }
            }
            if(runtime==null || Set.of("STOPPED","EXPIRED").contains(runtime.state())) {
                runtime=startInterval(device,runtime,received,received.plusSeconds(3600));
            } else {
                runtime=new RuntimeRow(runtime.deviceId(),runtime.userId(),runtime.instanceNo(),runtime.productCode(),true,
                        runtime.generation(),runtime.intervalStartedAt(),received.plusSeconds(3600),runtime.stopAt(),runtime.stopAfterCurrent(),
                        runtime.finishAfterAt(),runtime.nextStartAt(),runtime.currentTaskNo(),received,"RUNNING",null);
                mapper.updateRuntime(runtime);
            }
            return new PhoneOnlineResult(true,0,advance(device,runtime,received));
        });
    }

    /** A successful immediate stop records its cutoff now, even when older earned tasks need more batches. */
    public RuntimeRow requestStop(long userId,long deviceId,boolean afterCurrent) {
        requireEnabled();LocalDateTime stopped=now();
        return transaction.execute(status->{
            var device=owned(userId,deviceId);var runtime=mapper.lockRuntime(deviceId);
            if(runtime==null) {
                runtime=startInterval(device,null,stopped,null);
                return save(runtime,stopped,null,null,"STOPPED",afterCurrent?"AFTER_CURRENT":"DEVICE_STOPPED");
            }
            requireIdentity(device,runtime);
            LocalDateTime cutoff=afterCurrent?runtime.stopAt():earlier(runtime.stopAt(),stopped);
            LocalDateTime finishAfter=afterCurrent?earlier(runtime.finishAfterAt(),stopped):null;
            runtime=new RuntimeRow(runtime.deviceId(),runtime.userId(),runtime.instanceNo(),runtime.productCode(),runtime.phone(),
                    runtime.generation(),runtime.intervalStartedAt(),runtime.phoneValidUntil(),cutoff,afterCurrent,
                    finishAfter,runtime.nextStartAt(),runtime.currentTaskNo(),stopped,"CLOSING",null);
            mapper.updateRuntime(runtime);return advance(device,runtime,stopped);
        });
    }

    @Scheduled(fixedDelay=30000,initialDelay=30000)
    public void tick() {
        if(!enabled())return;
        LocalDateTime observed=now();
        for(var candidate:mapper.due(observed,BATCH_SIZE))process(candidate,observed,false);
        var candidates=mapper.initialDevices(initialCursor.get(),BATCH_SIZE);
        if(candidates.isEmpty())initialCursor.set(0);
        for(var candidate:candidates){initialCursor.set(candidate.deviceId());process(candidate,observed,true);}
    }
    private void process(Candidate candidate,LocalDateTime observed,boolean initial) {
        try {
            if(initial)transaction.execute(status->{
                var device=owned(candidate.userId(),candidate.deviceId());
                var runtime=mapper.lockRuntime(candidate.deviceId());
                if(!active(device) || device.pendingDeactivate())return null;
                if(runtime!=null)requireIdentity(device,runtime);
                if(runtime!=null && (!Set.of("STOPPED","EXPIRED").contains(runtime.state())
                        || !"DEVICE_PAUSED".equals(runtime.lastError()) && !device.activatedAt().isAfter(runtime.intervalStartedAt())))return null;
                startInterval(device,runtime,observed,null);return null;
            });
            transaction.execute(status->{
                var device=owned(candidate.userId(),candidate.deviceId());
                var runtime=mapper.lockRuntime(candidate.deviceId());
                if(runtime!=null){requireIdentity(device,runtime);advance(device,runtime,observed);}
                return null;
            });
        } catch(RuntimeException failure) {
            // Keep the original task and quote on a rolled-back financial/configuration failure; other devices continue.
            String reason=failure instanceof BizException?failure.getMessage():failure.getClass().getSimpleName();
            transaction.execute(status->{mapper.recordError(candidate.deviceId(),reason.substring(0,Math.min(128,reason.length())),observed.plusSeconds(30));return null;});
            LOG.warn("Simulated task deferred device={} reason={}",candidate.deviceId(),reason);
        }
    }

    private RuntimeRow startInterval(DeviceRow device,RuntimeRow previous,LocalDateTime start,LocalDateTime validUntil) {
        if(hasLegacyPending(device.userId(),device.deviceId()))
            throw error(409,"SIMULATED_LEGACY_NOT_DRAINED");
        var runtime=new RuntimeRow(device.deviceId(),device.userId(),device.instanceNo(),device.productCode(),phone(device),
                previous==null?1:previous.generation()+1,start,validUntil,null,false,null,start,null,start,"RUNNING",null);
        if((previous==null?mapper.insertRuntime(runtime):mapper.updateRuntime(runtime))!=1)
            throw error(409,"SIMULATED_RUNTIME_IDENTITY_CONFLICT");
        return runtime;
    }

    private RuntimeRow advance(DeviceRow device,RuntimeRow runtime,LocalDateTime observed) {
        LocalDateTime cutoff=earlier(runtime.stopAt(),runtime.phone()?runtime.phoneValidUntil():null);
        cutoff=earlier(cutoff,device.deactivatedAt());
        String stopReason=runtime.phone() && runtime.phoneValidUntil()!=null && runtime.phoneValidUntil().equals(cutoff)
                ?"PHONE_VALIDITY_EXPIRED":"DEVICE_STOPPED";
        if("DEVICE_PAUSED".equals(runtime.lastError()) && Objects.equals(runtime.stopAt(),cutoff))stopReason="DEVICE_PAUSED";
        if(!active(device) && device.deactivatedAt()==null) {
            LocalDateTime pausedAt=observed;
            if(device.dispatchPaused())pausedAt=earlier(pausedAt,device.dcUpdatedAt());
            if(device.pausedReason()!=null && !device.pausedReason().isBlank()
                    && !(phone(device) && Set.of("PHONE_OFFLINE","PHONE_LOW_BATTERY").contains(device.pausedReason())))
                pausedAt=earlier(pausedAt,device.runtimeUpdatedAt());
            if(device.activatedAt()==null || !Set.of("ACTIVE","ONLINE","BUSY","RUNNING").contains(device.status().toUpperCase(Locale.ROOT)))
                pausedAt=earlier(pausedAt,device.updatedAt());
            if(cutoff==null || pausedAt.isBefore(cutoff)) {
                cutoff=pausedAt;stopReason="DEVICE_PAUSED";
                runtime=new RuntimeRow(runtime.deviceId(),runtime.userId(),runtime.instanceNo(),runtime.productCode(),runtime.phone(),
                        runtime.generation(),runtime.intervalStartedAt(),runtime.phoneValidUntil(),cutoff,runtime.stopAfterCurrent(),
                        runtime.finishAfterAt(),runtime.nextStartAt(),runtime.currentTaskNo(),runtime.nextActionAt(),runtime.state(),"DEVICE_PAUSED");
            }
        }
        for(int n=0;n<CATCH_UP_LIMIT;n++) {
            if(runtime.currentTaskNo()==null) {
                if(runtime.stopAfterCurrent() && !runtime.nextStartAt().isBefore(runtime.finishAfterAt())
                        || device.pendingDeactivate() && !runtime.stopAfterCurrent() && runtime.stopAt()==null)
                    return save(runtime,runtime.nextStartAt(),null,null,"STOPPED","AFTER_CURRENT");
                if(cutoff!=null && !runtime.nextStartAt().isBefore(cutoff))
                    return save(runtime,runtime.nextStartAt(),null,null,"PHONE_VALIDITY_EXPIRED".equals(stopReason)?"EXPIRED":"STOPPED",stopReason);
                var policy=policies.policyAt(runtime.productCode(),runtime.nextStartAt());
                var pool=policy.taskTypes().stream().filter(SimulatedTaskPolicyService.TaskTypeSnapshot::enabled).toList();
                int policySteps=0;
                while(pool.isEmpty()) {
                    if(policySteps++>=CATCH_UP_LIMIT)
                        return save(runtime,runtime.nextStartAt(),null,observed.plusSeconds(30),"WAITING_CONFIG",null);
                    policy=policies.nextPolicyAfter(runtime.productCode(),runtime.nextStartAt(),earlier(earlier(observed,cutoff),runtime.finishAfterAt()));
                    if(policy==null) {
                        if(runtime.stopAfterCurrent() && !runtime.finishAfterAt().isAfter(observed)
                                && (cutoff==null || !runtime.finishAfterAt().isAfter(cutoff)))
                            return save(runtime,runtime.finishAfterAt(),null,null,"STOPPED","AFTER_CURRENT");
                        if(cutoff!=null && !cutoff.isAfter(observed))
                            return save(runtime,cutoff,null,null,"PHONE_VALIDITY_EXPIRED".equals(stopReason)?"EXPIRED":"STOPPED",stopReason);
                        return save(runtime,runtime.nextStartAt(),null,observed.plusSeconds(30),"WAITING_CONFIG",null);
                    }
                    runtime=save(runtime,policy.effectiveAt(),null,policy.effectiveAt(),"RUNNING",null);
                    if(cutoff!=null && !runtime.nextStartAt().isBefore(cutoff))
                        return save(runtime,runtime.nextStartAt(),null,null,"PHONE_VALIDITY_EXPIRED".equals(stopReason)?"EXPIRED":"STOPPED",stopReason);
                    if(runtime.stopAfterCurrent() && !runtime.nextStartAt().isBefore(runtime.finishAfterAt()))
                        return save(runtime,runtime.finishAfterAt(),null,null,"STOPPED","AFTER_CURRENT");
                    pool=policy.taskTypes().stream().filter(SimulatedTaskPolicyService.TaskTypeSnapshot::enabled).toList();
                }
                var type=pool.get(random.nextInt(pool.size()));
                var projection=E3DeviceCapacityPolicy.project(device.productCode(),device.deviceType(),device.purchasedAt(),device.activatedAt(),
                        runtime.nextStartAt(),policy.e3Config());
                BigDecimal reward=E3DeviceCapacityPolicy.applyCapacity(type.baseReward(),projection);
                var wallets=mapper.wallets(device.userId());
                if(wallets.size()!=1)throw error(409,"SIMULATED_WALLET_UNAVAILABLE");
                var wallet=wallets.get(0);String suffix=UUID.randomUUID().toString().replace("-","").toUpperCase(Locale.ROOT);
                BigDecimal daily=assignments.lockPaidCloudShareDailyNex(device.userId(),device.deviceId());
                var task=new TaskRow("SIM-"+suffix,"CTR-"+suffix,device.userId(),device.deviceId(),device.instanceNo(),runtime.generation(),
                        device.productId(),device.productCode(),device.productTier(),device.deviceName(),device.deviceType(),device.gpuModel(),device.vramTotalGb(),
                        type.taskTypeId(),type.typeVersion(),policy.version(),policy.e3Version(),type.name(),type.taskClass(),type.durationSeconds(),"NEX".equals(type.asset())?"UVT":type.asset(),
                        type.baseReward(),reward,projection.capacityPct().movePointLeft(2),daily==null?BigDecimal.ZERO:daily,
                        wallet.walletId(),wallet.incarnation(),runtime.nextStartAt(),runtime.nextStartAt().plusSeconds(type.durationSeconds()),"RUNNING",null,null,null);
                if(mapper.insertTask(task)!=1)throw error(409,"SIMULATED_TASK_CREATION_CONFLICT");
                runtime=save(runtime,runtime.nextStartAt(),task.taskNo(),earlier(task.dueAt(),cutoff),"RUNNING",null);
            }
            var task=mapper.lockTask(runtime.currentTaskNo());
            if(task==null || task.userId()!=runtime.userId() || task.deviceId()!=runtime.deviceId()
                    || task.generation()!=runtime.generation() || !"RUNNING".equals(task.status()))
                throw error(409,"SIMULATED_TASK_IDENTITY_CONFLICT");
            if(!task.dueAt().isAfter(observed) && (cutoff==null || !task.dueAt().isAfter(cutoff))) {
                LocalDateTime posted=now();
                if(mapper.completeTask(task.taskNo(),posted)!=1)throw error(409,"SIMULATED_TASK_SETTLEMENT_CONFLICT");
                settlement.settle(task,posted);
                runtime=save(runtime,task.dueAt(),null,task.dueAt(),"RUNNING",null);
                if(runtime.stopAfterCurrent() && !task.dueAt().isBefore(runtime.finishAfterAt())
                        || device.pendingDeactivate() && !runtime.stopAfterCurrent() && runtime.stopAt()==null)
                    return save(runtime,task.dueAt(),null,null,"STOPPED","AFTER_CURRENT");
                continue;
            }
            if(cutoff!=null && !cutoff.isAfter(observed)) {
                if(mapper.cancelTask(task.taskNo(),stopReason)!=1)throw error(409,"SIMULATED_TASK_CANCELLATION_CONFLICT");
                return save(runtime,cutoff,null,null,"PHONE_VALIDITY_EXPIRED".equals(stopReason)?"EXPIRED":"STOPPED",stopReason);
            }
            return save(runtime,runtime.nextStartAt(),task.taskNo(),earlier(task.dueAt(),cutoff),"RUNNING",null);
        }
        if(runtime.stopAt()!=null || runtime.stopAfterCurrent())
            return save(runtime,runtime.nextStartAt(),runtime.currentTaskNo(),runtime.nextActionAt(),"CLOSING",runtime.lastError());
        return runtime;
    }

    private RuntimeRow save(RuntimeRow r,LocalDateTime nextStart,String task,LocalDateTime nextAction,String state,String error) {
        if(error==null && r.stopAt()!=null && "DEVICE_PAUSED".equals(r.lastError()))error="DEVICE_PAUSED";
        var updated=new RuntimeRow(r.deviceId(),r.userId(),r.instanceNo(),r.productCode(),r.phone(),r.generation(),r.intervalStartedAt(),
                r.phoneValidUntil(),r.stopAt(),r.stopAfterCurrent(),r.finishAfterAt(),nextStart,task,nextAction,state,error);
        if(mapper.updateRuntime(updated)!=1)throw error(409,"SIMULATED_RUNTIME_IDENTITY_CONFLICT");
        if(task==null && nextAction==null && Set.of("STOPPED","EXPIRED").contains(state))
            lifecycle.finalizeStopped(r.userId(),r.deviceId());
        return updated;
    }
    private DeviceRow owned(long user,long device) {
        if(mapper.lockUser(user)==null)throw error(403,"SIMULATED_ACCOUNT_NOT_ACTIVE");
        var row=mapper.lockDevice(user,device);
        if(row==null)throw error(403,"SIMULATED_DEVICE_NOT_OWNED");return row;
    }
    private void requireActive(DeviceRow d){if(!active(d))throw error(409,"SIMULATED_DEVICE_NOT_ACTIVE");}
    private boolean active(DeviceRow d) {
        boolean phonePause=phone(d) && Set.of("PHONE_OFFLINE","PHONE_LOW_BATTERY").contains(Objects.toString(d.pausedReason(),""));
        return d.activatedAt()!=null && d.deactivatedAt()==null && Set.of("ACTIVE","ONLINE","BUSY","RUNNING").contains(d.status().toUpperCase(Locale.ROOT))
                && !d.dispatchPaused() && (d.pausedReason()==null || d.pausedReason().isBlank() || phonePause);
    }
    private static boolean phone(DeviceRow d){return Set.of("PHONE","MOBILE").contains(d.deviceType().toUpperCase(Locale.ROOT));}
    private void requireIdentity(DeviceRow d,RuntimeRow r) {
        if(d.userId()!=r.userId() || !d.instanceNo().equals(r.instanceNo()))throw error(409,"SIMULATED_RUNTIME_OWNER_CHANGED");
    }
    private void requireEnabled(){if(!enabled())throw error(404,"SIMULATED_TASKS_DISABLED");}
    private LocalDateTime now(){return LocalDateTime.ofInstant(clock.instant(),DateTimeFormatConfig.BUSINESS_ZONE).truncatedTo(ChronoUnit.MICROS);}
    private static LocalDateTime earlier(LocalDateTime a,LocalDateTime b){return a==null?b:b==null?a:a.isBefore(b)?a:b;}
    private static BizException error(int code,String message){return new BizException(code,message);}
}
