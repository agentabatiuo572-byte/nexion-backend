package ffdd.opsconsole.device.application;

import ffdd.opsconsole.device.mapper.AppTaskAssignmentMapper;
import ffdd.opsconsole.device.mapper.SimulatedTaskMapper;
import ffdd.opsconsole.device.mapper.SimulatedTaskMapper.*;
import ffdd.opsconsole.shared.capacity.E3DeviceCapacityPolicy;
import ffdd.opsconsole.shared.config.DateTimeFormatConfig;
import ffdd.opsconsole.shared.exception.BizException;
import jakarta.annotation.PreDestroy;
import java.math.BigDecimal;
import java.time.*;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.ContextClosedEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.env.Environment;
import org.springframework.scheduling.annotation.SchedulingConfigurer;
import org.springframework.scheduling.config.ScheduledTaskRegistrar;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

/** Independent SQL due consumers. Reads never issue work, extend a phone interval, or credit money. */
@Service
public class SimulatedTaskService implements SchedulingConfigurer {
    private static final Logger LOG=LoggerFactory.getLogger(SimulatedTaskService.class);
    private static final int BATCH_SIZE=500,CATCH_UP_LIMIT=12;
    private static final int INITIAL_THREADS = 20, DUE_THREADS = 40;
    private enum DueResult { EMPTY, ADVANCED, UNCHANGED }
    private final SimulatedTaskMapper mapper;
    private final AppTaskAssignmentMapper assignments;
    private final SimulatedTaskPolicyService policies;
    private final SimulatedTaskSettlementService settlement;
    private final HardwareDailyUvtService hardwareDailyUvt;
    private final SimulatedTaskLifecycleService lifecycle;
    private final TransactionTemplate transaction;
    private final Environment environment;
    private final Clock clock;
    private final DeviceMonthlyTargetService monthlyTargets;
    private final LocalDateTime liveDueFrom;
    private final LocalDateTime abandonBefore;
    private final AtomicLong initialCursor=new AtomicLong();
    private final ExecutorService initialWorkers;
    private final ScheduledThreadPoolExecutor dueWorkers;
    private final AtomicInteger dueTurn = new AtomicInteger();
    private boolean dueStarted;
    private volatile boolean stopping;
    private boolean moreWork;
    private Instant nextInitialAt;

    public SimulatedTaskService(SimulatedTaskMapper mapper,AppTaskAssignmentMapper assignments,
            SimulatedTaskPolicyService policies,SimulatedTaskSettlementService settlement,SimulatedTaskLifecycleService lifecycle,
            PlatformTransactionManager transactions,Environment environment,Clock clock,DeviceMonthlyTargetService monthlyTargets,
            HardwareDailyUvtService hardwareDailyUvt) {
        this.monthlyTargets=monthlyTargets;
        this.mapper=mapper;this.assignments=assignments;this.policies=policies;this.settlement=settlement;this.lifecycle=lifecycle;
        this.hardwareDailyUvt=hardwareDailyUvt;
        this.transaction=new TransactionTemplate(transactions);this.environment=environment;this.clock=clock;
        this.liveDueFrom=now();
        String boundary=environment.getProperty("nexion.simulated-tasks.abandon-before","");
        this.abandonBefore=boundary.isBlank()?null:LocalDateTime.parse(boundary);
        if(abandonBefore!=null && abandonBefore.isAfter(liveDueFrom))
            throw new IllegalArgumentException("SIMULATED_ABANDON_BOUNDARY_IN_FUTURE");
        LOG.info("Simulated task due fairness boundary={} abandonBefore={}",liveDueFrom,abandonBefore);
        AtomicInteger initialNumber = new AtomicInteger();
        initialWorkers = Executors.newFixedThreadPool(INITIAL_THREADS,
                job -> new Thread(job, "settlement-initial-" + initialNumber.incrementAndGet()));
        AtomicInteger dueNumber = new AtomicInteger();
        dueWorkers = new ScheduledThreadPoolExecutor(DUE_THREADS,
                job -> new Thread(job, "settlement-due-" + dueNumber.incrementAndGet()));
        dueWorkers.setExecuteExistingDelayedTasksAfterShutdownPolicy(false);
    }

    @EventListener(ContextClosedEvent.class)
    public void contextClosed() {
        // Only scheduling admission uses this lock; no database work runs under it.
        synchronized (dueWorkers) {
            stopping = true;
            dueWorkers.shutdown();
        }
    }

    @PreDestroy
    void stopSettlementWorkers() {
        contextClosed();
        // The initial tick retains this monitor until all its user groups drain.
        synchronized (this) {
            initialWorkers.shutdown();
        }
        boolean interrupted = false;
        for (ExecutorService workers : List.of(dueWorkers, initialWorkers)) {
            while (!workers.isTerminated()) {
                try {
                    workers.awaitTermination(1, TimeUnit.DAYS);
                } catch (InterruptedException failure) {
                    interrupted = true;
                }
            }
        }
        if (interrupted) Thread.currentThread().interrupt();
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
                    runtime=advance(device,runtime,received,false);
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
            return new PhoneOnlineResult(true,0,advance(device,runtime,received,false));
        });
    }

    /** Null means this device still follows its non-simulated deactivation path. */
    public Boolean slotReplacementWaits(long userId, long deviceId, String instanceNo) {
        if (!manages(deviceId)) return null;
        var runtime = mapper.runtime(deviceId);
        boolean legacyPending = hasLegacyPending(userId, deviceId);
        if (runtime == null) return legacyPending ? null : false;
        if (legacyPending || runtime.userId() != userId || !instanceNo.equals(runtime.instanceNo())) {
            throw error(409, "SIMULATED_REPLACEMENT_TIMING_UNAVAILABLE");
        }
        if (runtime.currentTaskNo() == null) return false;

        var device = mapper.readOwnedDevice(userId, deviceId);
        var task = mapper.task(runtime.currentTaskNo());
        if (device == null || !instanceNo.equals(device.instanceNo()) || task == null
                || task.userId() != userId || task.deviceId() != deviceId || task.generation() != runtime.generation()
                || !"RUNNING".equals(task.status())) {
            throw error(409, "SIMULATED_REPLACEMENT_TIMING_UNAVAILABLE");
        }
        LocalDateTime observed = now();
        LocalDateTime cutoff = earlier(runtime.stopAt(), runtime.phone() ? runtime.phoneValidUntil() : null);
        cutoff = earlier(cutoff, earlier(device.deactivatedAt(), inactiveCutoff(device, observed)));
        return (abandonBefore == null || !task.startedAt().isBefore(abandonBefore))
                && task.dueAt().isAfter(observed) && (cutoff == null || cutoff.isAfter(observed));
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
            mapper.updateRuntime(runtime);return advance(device,runtime,stopped,false);
        });
    }

    void cancelPendingSlotStop(long userId, long deviceId) {
        mapper.cancelPendingSlotStop(userId, deviceId);
    }

    /** The caller holds the user and OWNED device locks until the replacement transaction ends. */
    RuntimeRow lockReplacementRuntime(long userId, long deviceId, String instanceNo, boolean activated, boolean computeV2Enrolled) {
        if (!TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("SIMULATED_LIFECYCLE_TRANSACTION_REQUIRED");
        }
        var runtime = mapper.lockRuntime(deviceId);
        if (runtime != null && (runtime.userId() != userId || runtime.deviceId() != deviceId
                || !instanceNo.equals(runtime.instanceNo()))) {
            throw error(409, "SIMULATED_RUNTIME_OWNER_CHANGED");
        }
        if (mapper.unresolvedV2(userId, deviceId) != 0
                || mapper.hasUncancellableReplacementTask(userId, deviceId, runtime != null || !activated)) {
            throw error(409, "COMPUTE_V2_LEGACY_REQUEST_UNRESOLVED");
        }
        if (runtime == null) return null;

        boolean terminal = Set.of("STOPPED", "EXPIRED").contains(runtime.state());
        if (!terminal && !Set.of("RUNNING", "CLOSING", "ERROR", "WAITING_CONFIG").contains(runtime.state())) {
            throw error(409, "SIMULATED_RUNTIME_IDENTITY_CONFLICT");
        }
        boolean drained = terminal && runtime.currentTaskNo() == null && runtime.nextActionAt() == null;
        if (computeV2Enrolled) {
            if (drained) return null;
            throw error(409, "COMPUTE_V2_DEVICE_BUSY");
        }
        if (!drained && (!activated || !manages(deviceId))) throw error(409, "SIMULATED_DEVICE_BUSY");
        return runtime;
    }

    /** Even a due task is cancelled before settlement. The runtime lock is still held. */
    void cancelForReplacement(RuntimeRow runtime) {
        stopWithoutSuccessor(runtime, null);
    }

    void stopForH5Login(RuntimeRow runtime) {
        stopWithoutSuccessor(runtime, ownedDevice(runtime.userId(), runtime.deviceId()));
    }

    private void stopWithoutSuccessor(RuntimeRow runtime, DeviceRow h5Phone) {
        LocalDateTime cutoff = now();
        if (runtime.currentTaskNo() != null) {
            var task = mapper.lockTask(runtime.currentTaskNo());
            if (task == null || !runtime.currentTaskNo().equals(task.taskNo()) || task.userId() != runtime.userId()
                    || task.deviceId() != runtime.deviceId() || !runtime.instanceNo().equals(task.instanceNo())
                    || task.generation() != runtime.generation()) {
                throw error(409, "SIMULATED_TASK_IDENTITY_CONFLICT");
            }
            if (task.postedAt() != null) throw error(409, "SIMULATED_TASK_ALREADY_POSTED");
            if (!"RUNNING".equals(task.status()) || !Set.of("RUNNING", "CLOSING", "ERROR").contains(runtime.state())) {
                throw error(409, "SIMULATED_TASK_IDENTITY_CONFLICT");
            }
            if (h5Phone != null
                    && !task.dueAt().isAfter(earlier(earlier(cutoff, runtime.stopAt()),
                            earlier(runtime.phoneValidUntil(), inactiveCutoff(h5Phone, cutoff))))
                    && (abandonBefore == null || !task.startedAt().isBefore(abandonBefore))) {
                settleDueTask(h5Phone, task, true);
            } else {
                if (mapper.cancelTask(task.taskNo(), SimulatedTaskMapper.REPLACEMENT_STOP_REASON) != 1)
                    throw error(409, "SIMULATED_TASK_CANCELLATION_CONFLICT");
                monthlyTargets.cancel(task);
            }
        }
        var stopped = new RuntimeRow(runtime.deviceId(), runtime.userId(), runtime.instanceNo(), runtime.productCode(),
                runtime.phone(), runtime.generation(), runtime.intervalStartedAt(), runtime.phoneValidUntil(), cutoff,
                false, null, runtime.nextStartAt(), null, null, "STOPPED", SimulatedTaskMapper.REPLACEMENT_STOP_REASON);
        if (mapper.updateRuntime(stopped) != 1) throw error(409, "SIMULATED_RUNTIME_IDENTITY_CONFLICT");
    }

    @Override
    public void configureTasks(ScheduledTaskRegistrar tasks) {
        tasks.addTriggerTask(this::tick, context -> {
            Instant completed=context.lastCompletion();
            if(completed==null)return context.getClock().instant().plusSeconds(30);
            Instant next=completed.plusMillis(moreWork ? 100 : 30_000);
            return !stopping && nextInitialAt!=null && nextInitialAt.isBefore(next) ? nextInitialAt : next;
        });
    }

    public synchronized void tick() {
        moreWork=false;
        Instant initialAt=nextInitialAt;
        nextInitialAt=null;
        if(stopping || !enabled())return;
        if(!dueStarted) {
            dueStarted=true;
            for(int worker=0;worker<DUE_THREADS;worker++)scheduleDue(0);
        }
        if(stopping)return;
        boolean scanInitial=initialAt==null || !clock.instant().isBefore(initialAt);
        var candidates=scanInitial ? mapper.initialDevices(initialCursor.get(),BATCH_SIZE,now()) : List.<Candidate>of();
        if(scanInitial && candidates.isEmpty()) {
            initialCursor.set(0);
            initialAt=clock.instant().plusSeconds(5);
        }
        Map<Long,List<Candidate>> byUser=new LinkedHashMap<>();
        for(var candidate:candidates)
            byUser.computeIfAbsent(candidate.userId(),ignored->new ArrayList<>()).add(candidate);
        List<CompletableFuture<Boolean>> batches=new ArrayList<>();
        for(var group:byUser.values()) {
            batches.add(CompletableFuture.supplyAsync(()->{
                boolean advanced=false;
                for(var candidate:group) {
                    if(stopping)return advanced;
                    advanced|=processInitial(candidate);
                }
                return advanced;
            },initialWorkers));
        }
        // Drain every user group before advancing the cursor or releasing the shutdown monitor.
        CompletableFuture.allOf(batches.toArray(CompletableFuture[]::new)).join();
        if(stopping)return;
        if(!candidates.isEmpty()) {
            initialCursor.set(candidates.get(candidates.size()-1).deviceId());
            initialAt=clock.instant().plusMillis(100);
        }
        boolean advancedInitial=batches.stream().anyMatch(batch->batch.join());
        moreWork=(candidates.size()==BATCH_SIZE && advancedInitial) || (advancedInitial && mapper.hasDue(now()));
        nextInitialAt=initialAt;
    }
    private void scheduleDue(long delayMillis) {
        synchronized (dueWorkers) {
            if(!stopping)dueWorkers.schedule(this::consumeDue,delayMillis,TimeUnit.MILLISECONDS);
        }
    }

    private void consumeDue() {
        if(stopping)return;
        long delayMillis=5_000;
        try {
            if(enabled()) {
                DueResult result=processNextDue(now(),(dueTurn.getAndIncrement() & 1)==0);
                delayMillis=result==DueResult.EMPTY ? 1_000 : 0;
            }
        } catch(RuntimeException failure) {
            LOG.warn("Simulated due consumer failed before completion; retrying",failure);
        } finally {
            // Each completed slot supplies only its own successor, independent of initial scans.
            scheduleDue(delayMillis);
        }
    }

    private DueResult processNextDue(LocalDateTime observed, boolean currentFirst) {
        var claimed = new AtomicReference<DueCandidate>();
        try {
            return transaction.execute(status -> {
                if(stopping)return DueResult.EMPTY;
                var candidate = currentFirst ? mapper.claimCurrentDue(observed) : mapper.claimDue(observed);
                if(candidate==null && currentFirst)candidate=mapper.claimDue(observed);
                if (candidate == null) return DueResult.EMPTY;
                claimed.set(candidate);
                if (!candidate.activeAccount()) throw error(403,"SIMULATED_ACCOUNT_NOT_ACTIVE");
                var device = mapper.lockSettlementDevice(candidate.userId(),candidate.deviceId());
                if(device==null)throw error(403,"SIMULATED_DEVICE_NOT_SETTLEABLE");
                boolean advanced=advanceCandidate(device,observed,false);
                if(advanced && !stopping) {
                    int additional=0;
                    for(var next:mapper.runtimesByUser(candidate.userId())) {
                        if(stopping || additional==5)break;
                        if(next.deviceId()==candidate.deviceId() || next.nextActionAt()==null
                                || next.nextActionAt().isAfter(observed))continue;
                        additional++;
                        var priorClaimed=claimed.get();
                        claimed.set(new DueCandidate(next.userId(),next.deviceId(),next.generation(),
                                next.currentTaskNo(),next.nextActionAt(),candidate.activeAccount()));
                        var additionalDevice=mapper.lockDevice(next.userId(),next.deviceId());
                        if(additionalDevice==null) {
                            claimed.set(priorClaimed);
                            continue;
                        }
                        advanced|=advanceCandidate(additionalDevice,now(),false);
                    }
                }
                return advanced ? DueResult.ADVANCED : DueResult.UNCHANGED;
            });
        } catch (RuntimeException failure) {
            var candidate = claimed.get();
            if (candidate == null) throw failure;
            defer(candidate.deviceId(),candidate,failure);
            return DueResult.UNCHANGED;
        }
    }

    private boolean processInitial(Candidate candidate) {
        try {
            transaction.execute(status->{
                if(stopping)return null;
                var device=owned(candidate.userId(),candidate.deviceId());
                var runtime=mapper.lockRuntime(candidate.deviceId());
                if(!active(device) || device.pendingDeactivate())return null;
                if(runtime!=null)requireIdentity(device,runtime);
                if(runtime!=null && (!Set.of("STOPPED","EXPIRED").contains(runtime.state())
                        || !"DEVICE_PAUSED".equals(runtime.lastError()) && !device.activatedAt().isAfter(runtime.intervalStartedAt())))return null;
                startInterval(device,runtime,now(),null);return null;
            });
            if(stopping)return false;
            return Boolean.TRUE.equals(transaction.execute(status->{
                if(stopping)return false;
                return advanceCandidate(owned(candidate.userId(),candidate.deviceId()),now(),true);
            }));
        } catch(RuntimeException failure) {
            defer(candidate.deviceId(),null,failure);
            return false;
        }
    }

    private boolean advanceCandidate(DeviceRow device,LocalDateTime observed,boolean initial) {
        var runtime=mapper.lockRuntime(device.deviceId());
        if(runtime==null)return false;
        requireIdentity(device,runtime);
        if ("STOPPED".equals(runtime.state()) && SimulatedTaskMapper.REPLACEMENT_STOP_REASON.equals(runtime.lastError())) {
            return false;
        }
        if(!initial && (runtime.nextActionAt()==null || runtime.nextActionAt().isAfter(observed)))return false;
        if(!initial && "ERROR".equals(runtime.state()) && "IllegalArgumentException".equals(runtime.lastError())
                && !runtime.phone() && runtime.currentTaskNo()==null && runtime.stopAt()==null
                && !runtime.stopAfterCurrent() && runtime.finishAfterAt()==null
                && runtime.intervalStartedAt().equals(runtime.nextStartAt()) && active(device) && !device.pendingDeactivate()) {
            LocalDateTime recoveredAt=now();
            LocalDateTime lifecycleStartedAt=device.purchasedAt()!=null?device.purchasedAt():device.activatedAt();
            if(lifecycleStartedAt!=null && lifecycleStartedAt.isAfter(runtime.nextStartAt())
                    && !lifecycleStartedAt.isAfter(recoveredAt)
                    && !mapper.hasGenerationTasks(runtime.userId(),runtime.deviceId(),runtime.generation())) {
                runtime=new RuntimeRow(runtime.deviceId(),runtime.userId(),runtime.instanceNo(),runtime.productCode(),runtime.phone(),
                        runtime.generation(),recoveredAt,runtime.phoneValidUntil(),runtime.stopAt(),runtime.stopAfterCurrent(),
                        runtime.finishAfterAt(),recoveredAt,runtime.currentTaskNo(),recoveredAt,"RUNNING",null);
                observed=recoveredAt;
            }
        }
        var after=advance(device,runtime,observed,true);
        return !Objects.equals(after.currentTaskNo(),runtime.currentTaskNo())
                || !Objects.equals(after.nextStartAt(),runtime.nextStartAt()) || !after.state().equals(runtime.state());
    }

    private void defer(long deviceId,DueCandidate due,RuntimeException failure) {
        // Record the retry after rollback, guarding a due slot against concurrent advancement.
        String reason=failure instanceof BizException?failure.getMessage():failure.getClass().getSimpleName();
        String error=reason.substring(0,Math.min(128,reason.length()));
        LocalDateTime retryAt=now().plusSeconds(30);
        int recorded=transaction.execute(status->due==null?mapper.recordError(deviceId,error,retryAt):mapper.recordDueError(due,error,retryAt));
        if(due==null || recorded!=0)LOG.warn("Simulated task deferred device={} reason={}",deviceId,reason,failure);
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

    private RuntimeRow advance(DeviceRow device,RuntimeRow runtime,LocalDateTime observed,boolean accountLockedBeforeSnapshot) {
        LocalDateTime cutoff=earlier(runtime.stopAt(),runtime.phone()?runtime.phoneValidUntil():null);
        cutoff=earlier(cutoff,device.deactivatedAt());
        String stopReason=runtime.phone() && runtime.phoneValidUntil()!=null && runtime.phoneValidUntil().equals(cutoff)
                ?"PHONE_VALIDITY_EXPIRED":"DEVICE_STOPPED";
        if("DEVICE_PAUSED".equals(runtime.lastError()) && Objects.equals(runtime.stopAt(),cutoff))stopReason="DEVICE_PAUSED";
        if(device.deactivatedAt()!=null && mapper.refundedOrderDevice(device.userId(),device.deviceId()))stopReason="ORDER_REFUND";
        LocalDateTime pausedAt=inactiveCutoff(device,observed);
        if(pausedAt!=null && (cutoff==null || pausedAt.isBefore(cutoff))) {
            cutoff=pausedAt;stopReason="DEVICE_PAUSED";
            runtime=new RuntimeRow(runtime.deviceId(),runtime.userId(),runtime.instanceNo(),runtime.productCode(),runtime.phone(),
                    runtime.generation(),runtime.intervalStartedAt(),runtime.phoneValidUntil(),cutoff,runtime.stopAfterCurrent(),
                    runtime.finishAfterAt(),runtime.nextStartAt(),runtime.currentTaskNo(),runtime.nextActionAt(),runtime.state(),"DEVICE_PAUSED");
        }
        LocalDateTime knownTargetCycleStart=null;
        LocalDateTime directSuccessorStart=null;
        for(int n=0;n<CATCH_UP_LIMIT;n++) {
            TaskRow insertedTask=null;
            if(runtime.currentTaskNo()==null) {
                if(runtime.stopAfterCurrent() && !runtime.finishAfterAt().isAfter(observed))
                    return save(runtime,runtime.nextStartAt(),null,null,"STOPPED","AFTER_CURRENT");
                if(cutoff!=null && !cutoff.isAfter(observed))
                    return save(runtime,cutoff,null,null,"PHONE_VALIDITY_EXPIRED".equals(stopReason)?"EXPIRED":"STOPPED",stopReason);
                if(runtime.stopAfterCurrent() && !runtime.nextStartAt().isBefore(runtime.finishAfterAt())
                        || device.pendingDeactivate() && !runtime.stopAfterCurrent() && runtime.stopAt()==null)
                    return save(runtime,runtime.nextStartAt(),null,null,"STOPPED","AFTER_CURRENT");
                if(cutoff!=null && !runtime.nextStartAt().isBefore(cutoff))
                    return save(runtime,runtime.nextStartAt(),null,null,"PHONE_VALIDITY_EXPIRED".equals(stopReason)?"EXPIRED":"STOPPED",stopReason);
                // An issued predecessor completed in this transaction may retain its not-yet-due successor.
                // Cold/stale empty runtimes and the fixed history cutoff keep the original skip rule.
                if(abandonBefore!=null && runtime.nextStartAt().isBefore(abandonBefore)
                        || runtime.nextStartAt().isBefore(observed.minusSeconds(60))
                            && !runtime.nextStartAt().equals(directSuccessorStart)) {
                    LOG.info("Simulated unissued history skipped device={} from={} to={} abandonBefore={}",
                            device.deviceId(),runtime.nextStartAt(),observed,abandonBefore);
                    runtime=save(runtime,observed,null,observed,"RUNNING",null);
                }
                // BUG-526: 手机任务池还没有可发布的 PHONE 任务类型时，策略层正常返回空池(=null)，
                // 由下面既有分支落 WAITING_CONFIG；这里不捕异常(跨 bean 事务代理会把外层标记 rollback-only)。
                var policy=policies.dispatchPolicyAt(runtime.productCode(),runtime.nextStartAt());
                var pool=policy==null?List.<SimulatedTaskPolicyService.TaskTypeSnapshot>of()
                        :policy.taskTypes().stream().filter(SimulatedTaskPolicyService.TaskTypeSnapshot::enabled).toList();
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
                        var future=policies.nextPolicyAfter(runtime.productCode(),runtime.nextStartAt(),observed.plusMinutes(30));
                        var next=earlier(observed.plusMinutes(30),future==null?null:future.effectiveAt());
                        next=earlier(earlier(next,cutoff),runtime.finishAfterAt());
                        if(runtime.stopAt()!=null || runtime.stopAfterCurrent())
                            return save(runtime,runtime.nextStartAt(),null,earlier(next,observed.plusSeconds(30)),"CLOSING",runtime.lastError());
                        return save(runtime,runtime.nextStartAt(),null,next,"WAITING_CONFIG",null);
                    }
                    runtime=save(runtime,policy.effectiveAt(),null,policy.effectiveAt(),"RUNNING",null);
                    directSuccessorStart=null;
                    if(cutoff!=null && !runtime.nextStartAt().isBefore(cutoff))
                        return save(runtime,runtime.nextStartAt(),null,null,"PHONE_VALIDITY_EXPIRED".equals(stopReason)?"EXPIRED":"STOPPED",stopReason);
                    if(runtime.stopAfterCurrent() && !runtime.nextStartAt().isBefore(runtime.finishAfterAt()))
                        return save(runtime,runtime.finishAfterAt(),null,null,"STOPPED","AFTER_CURRENT");
                    pool=policy.taskTypes().stream().filter(SimulatedTaskPolicyService.TaskTypeSnapshot::enabled).toList();
                    if(!pool.isEmpty() && !runtime.phone() && policy.allowedPoolCodes().stream().anyMatch(p->!"PHONE".equals(p))) {
                        // Logical empty -> DEVICE runnable, including catch-up without a persisted WAITING_CONFIG.
                        // Persist the shifted cursor before waiting; a retry at that cursor never offsets again.
                        var start=policy.effectiveAt().plusSeconds(wakeOffset(device.deviceId(),policy.effectiveAt()));
                        runtime=save(runtime,start,null,earlier(earlier(start,cutoff),runtime.finishAfterAt()),"RUNNING",null);
                        pool=List.of();
                        break;
                    }
                }
                if(pool.isEmpty()) {
                    if(runtime.nextStartAt().isAfter(observed))return runtime;
                    continue; // re-read policy and all lifecycle bounds at the actual offset start
                }
                if(runtime.nextStartAt().isAfter(observed))
                    return save(runtime,runtime.nextStartAt(),null,earlier(earlier(runtime.nextStartAt(),cutoff),runtime.finishAfterAt()),"RUNNING",null);
                pool=pool.stream().sorted(Comparator.comparing(SimulatedTaskPolicyService.TaskTypeSnapshot::taskTypeId)).toList();
                var type=pool.get(DeviceMonthlyTargetPacing.select(device.deviceId(),runtime.generation(),runtime.nextStartAt(),pool.size()));
                if(type.poolCode()!=null && !"PHONE".equals(type.poolCode()) && (runtime.phone() || policy.monthlyTargetUsdt()==null))
                    throw error(422,"SKU_POOL_TARGET_REQUIRED");
                if(runtime.nextStartAt().equals(directSuccessorStart)
                        && !runtime.nextStartAt().plusSeconds(type.durationSeconds()).isAfter(observed)) {
                    LOG.info("Simulated unissued history skipped device={} from={} to={} abandonBefore={}",
                            device.deviceId(),runtime.nextStartAt(),observed,abandonBefore);
                    runtime=save(runtime,observed,null,observed,"RUNNING",null);
                    directSuccessorStart=null;
                    continue;
                }
                var projection=E3DeviceCapacityPolicy.project(device.seriesCode(),device.deviceType(),device.purchasedAt(),device.activatedAt(),
                        runtime.nextStartAt(),policy.e3Config());
                BigDecimal reward=E3DeviceCapacityPolicy.applyCapacity(type.baseReward(),projection);
                var wallets=mapper.wallets(device.userId());
                if(wallets.size()!=1)throw error(409,"SIMULATED_WALLET_UNAVAILABLE");
                DeviceMonthlyTargetService.Quote target=null;
                if(policy.monthlyTargetUsdt()!=null) {
                    if(phone(device))throw error(422,"MONTHLY_TARGET_DEVICE_INELIGIBLE");
                    target=monthlyTargets.reserve(device,policy,runtime.nextStartAt(),type,pool,knownTargetCycleStart);
                    reward=target.reward();
                }
                var wallet=wallets.get(0);String suffix=UUID.randomUUID().toString().replace("-","").toUpperCase(Locale.ROOT);
                LocalDateTime dueAt=runtime.nextStartAt().plusSeconds(type.durationSeconds());
                BigDecimal daily=assignments.lockPaidCloudShareDailyNex(device.userId(),device.deviceId());
                if(daily==null) daily=hardwareDailyUvt.amount(device.userId(),device.deviceId(),device.productCode(),device.seriesCode(),dueAt);
                var task=new TaskRow("SIM-"+suffix,"CTR-"+suffix,device.userId(),device.deviceId(),device.instanceNo(),runtime.generation(),
                        device.productId(),device.productCode(),device.productTier(),device.deviceName(),device.deviceType(),device.gpuModel(),device.vramTotalGb(),
                        type.taskTypeId(),type.typeVersion(),policy.version(),policy.e3Version(),type.name(),type.taskClass(),type.durationSeconds(),"NEX".equals(type.asset())?"UVT":type.asset(),
                        type.baseReward(),reward,(target==null?projection.capacityPct():target.capacityPct()).movePointLeft(2),daily,
                        wallet.walletId(),wallet.incarnation(),runtime.nextStartAt(),dueAt,"RUNNING",null,null,null,target==null?null:target.cycleStart(),target==null?null:target.snapshotJson());
                if(mapper.insertTask(task)!=1)throw error(409,"SIMULATED_TASK_CREATION_CONFLICT");
                insertedTask=task;
                runtime=save(runtime,runtime.nextStartAt(),task.taskNo(),earlier(task.dueAt(),cutoff),"RUNNING",null);
            }
            var task=insertedTask!=null?insertedTask:mapper.lockTask(runtime.currentTaskNo());
            if(task==null || task.userId()!=runtime.userId() || task.deviceId()!=runtime.deviceId()
                    || task.generation()!=runtime.generation() || !"RUNNING".equals(task.status()))
                throw error(409,"SIMULATED_TASK_IDENTITY_CONFLICT");
            if(abandonBefore!=null && task.startedAt().isBefore(abandonBefore)) {
                if(task.postedAt()!=null)throw error(409,"SIMULATED_TASK_ALREADY_POSTED");
                mapper.wallets(task.userId());
                if(mapper.cancelTask(task.taskNo(),"HISTORY_ABANDONED")!=1)
                    throw error(409,"SIMULATED_TASK_CANCELLATION_CONFLICT");
                monthlyTargets.cancel(task);
                LOG.info("Simulated task history abandoned device={} task={} startedAt={} abandonBefore={}",
                        task.deviceId(),task.taskNo(),task.startedAt(),abandonBefore);
                runtime=save(runtime,observed,null,observed,"RUNNING",null);
                continue;
            }
            if(!task.dueAt().isAfter(observed) && (cutoff==null || !task.dueAt().isAfter(cutoff))) {
                settleDueTask(device,task,accountLockedBeforeSnapshot);
                knownTargetCycleStart=task.targetCycleStart();
                directSuccessorStart=task.dueAt();
                // The next durable save remains in this same transaction; terminal paths still save below.
                runtime=new RuntimeRow(runtime.deviceId(),runtime.userId(),runtime.instanceNo(),runtime.productCode(),runtime.phone(),
                        runtime.generation(),runtime.intervalStartedAt(),runtime.phoneValidUntil(),runtime.stopAt(),runtime.stopAfterCurrent(),
                        runtime.finishAfterAt(),task.dueAt(),null,task.dueAt(),"RUNNING",
                        runtime.stopAt()!=null && "DEVICE_PAUSED".equals(runtime.lastError())?"DEVICE_PAUSED":null);
                if(runtime.stopAfterCurrent() && !task.dueAt().isBefore(runtime.finishAfterAt())
                        || device.pendingDeactivate() && !runtime.stopAfterCurrent() && runtime.stopAt()==null)
                    return save(runtime,task.dueAt(),null,null,"STOPPED","ORDER_REFUND".equals(stopReason)?"ORDER_REFUND":"AFTER_CURRENT");
                continue;
            }
            if(cutoff!=null && !cutoff.isAfter(observed)) {
                mapper.wallets(task.userId());
                if(mapper.cancelTask(task.taskNo(),stopReason)!=1)throw error(409,"SIMULATED_TASK_CANCELLATION_CONFLICT");
                monthlyTargets.cancel(task);
                return save(runtime,cutoff,null,null,"PHONE_VALIDITY_EXPIRED".equals(stopReason)?"EXPIRED":"STOPPED",stopReason);
            }
            if(insertedTask!=null)return runtime;
            return save(runtime,runtime.nextStartAt(),task.taskNo(),earlier(task.dueAt(),cutoff),"RUNNING",null);
        }
        if(runtime.stopAt()!=null || runtime.stopAfterCurrent())
            return save(runtime,runtime.nextStartAt(),runtime.currentTaskNo(),runtime.nextActionAt(),"CLOSING",runtime.lastError());
        return save(runtime,runtime.nextStartAt(),runtime.currentTaskNo(),runtime.nextActionAt(),runtime.state(),runtime.lastError());
    }

    private void settleDueTask(DeviceRow device, TaskRow task, boolean accountLockedBeforeSnapshot) {
        LocalDateTime posted = now();
        // Resolve against the completion date, including tasks dispatched before B52.
        BigDecimal daily = hardwareDailyUvt.appliesTo(device.seriesCode())
                ? hardwareDailyUvt.amount(task.userId(), task.deviceId(), task.productCode(), device.seriesCode(), task.dueAt())
                : task.cloudDailyNex();
        if (mapper.completeTask(task.taskNo(), posted, daily) != 1)
            throw error(409, "SIMULATED_TASK_SETTLEMENT_CONFLICT");
        settlement.settle(task, posted, daily, accountLockedBeforeSnapshot);
    }

    private LocalDateTime inactiveCutoff(DeviceRow device, LocalDateTime observed) {
        if (active(device) || device.deactivatedAt() != null) return null;
        LocalDateTime pausedAt = observed;
        if (device.pausedReason()!=null && !device.pausedReason().isBlank()
                && !(phone(device) && Set.of("PHONE_OFFLINE","PHONE_LOW_BATTERY").contains(device.pausedReason())))
            pausedAt = earlier(pausedAt,device.runtimeUpdatedAt());
        if (device.activatedAt()==null || !Set.of("ACTIVE","ONLINE","BUSY","RUNNING").contains(device.status().toUpperCase(Locale.ROOT)))
            pausedAt = earlier(pausedAt,device.updatedAt());
        return pausedAt;
    }

    /** UTF-8, business-zone ISO local timestamp with exactly six fractional digits. */
    private static int wakeOffset(long device,LocalDateTime effectiveAt) {
        String input="SIM-WAKE-1|"+device+"|"+effectiveAt.format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSSSSS"));
        try {
            byte[] hash=java.security.MessageDigest.getInstance("SHA-256").digest(input.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            return new java.math.BigInteger(1,hash).mod(java.math.BigInteger.valueOf(300)).intValue();
        } catch(java.security.NoSuchAlgorithmException e) {throw new IllegalStateException(e);}
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
        return ownedDevice(user,device);
    }
    private DeviceRow ownedDevice(long user,long device) {
        var row=mapper.lockDevice(user,device);
        if(row==null)throw error(403,"SIMULATED_DEVICE_NOT_OWNED");return row;
    }
    private void requireActive(DeviceRow d){if(!active(d))throw error(409,"SIMULATED_DEVICE_NOT_ACTIVE");}
    private boolean active(DeviceRow d) {
        boolean phonePause=phone(d) && Set.of("PHONE_OFFLINE","PHONE_LOW_BATTERY").contains(Objects.toString(d.pausedReason(),""));
        return d.activatedAt()!=null && d.deactivatedAt()==null && Set.of("ACTIVE","ONLINE","BUSY","RUNNING").contains(d.status().toUpperCase(Locale.ROOT))
                && !d.opsPaused()
                && (d.pausedReason()==null || d.pausedReason().isBlank() || phonePause);
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
