package ffdd.opsconsole.device.compute.v2;

import ffdd.opsconsole.device.compute.v2.ComputeV2Event.*;
import java.time.*;
import java.util.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

/** Trusted adapters validate eligibility and real proof before entering this runtime. */
@Service
@ConditionalOnProperty(name = "nexion.compute-v2.enabled", havingValue = "true")
public class ComputeV2Runtime {
    private final ComputeV2RedisStore store;
    private final ComputeV2MessageLog log;
    private final ComputeV2Properties config;
    private final Clock clock;
    private final ComputeV2Persistence persistence;
    public record RuntimeView(ComputeV2Event event, String status, String executorId, Instant leaseExpiresAt,
            long pendingUsdtMicros, long pendingNexMicros) { }
    public record PendingSummary(long pendingUsdtMicros,long pendingNexMicros,long pendingTaskCount,long pendingNexTaskCount) { }
    public record PendingVerifiedSnapshot(PendingSummary summary,List<ComputeV2Event> events) {
        public PendingVerifiedSnapshot { events = List.copyOf(events); }
    }

    public ComputeV2Runtime(ComputeV2RedisStore store, ComputeV2MessageLog log, ComputeV2Properties config, Clock clock) {
        this(store,log,config,clock,null);
    }
    @Autowired
    public ComputeV2Runtime(ComputeV2RedisStore store,ComputeV2MessageLog log,ComputeV2Properties config,Clock clock,
            ComputeV2Persistence persistence) {
        this.store = store; this.log = log; this.config = config; this.clock = clock; this.persistence = persistence;
    }

    public ComputeV2Event issue(long userId, long deviceId, String instanceNo, TaskSnapshot frozen) {
        String request = userId + ":" + deviceId + ":" + frozen.requestId();
        String mapping = store.value("request:"+request);
        String cached = store.value("request-event:"+request);
        if (cached == null && mapping != null) cached = store.field("issued-events",mapping.substring(0,mapping.lastIndexOf('|')));
        if (cached != null) {
            var original = store.decode(cached,ComputeV2Event.class);
            return store.persisted(original.eventId()) || store.field("posted-requests",original.taskNo()) != null ? original : send(original);
        }
        String recoveredAt = store.value("recovered-at");
        boolean coldRequest = frozen.issuedAt().isBefore(clock.instant().minusSeconds(config.getPostedDedupSeconds()))
                || recoveredAt != null && frozen.issuedAt().toEpochMilli()<Long.parseLong(recoveredAt);
        if (mapping != null || coldRequest) return historicalIssue(userId,deviceId,frozen.requestId());
        if (!config.isAccepting()) throw new IllegalStateException("COMPUTE_V2_ADMISSION_PAUSED");
        String candidate = "CV2-" + UUID.randomUUID().toString().replace("-", "").toUpperCase(Locale.ROOT);
        List<?> reserved = store.reserve("reserve",request,Long.toString(deviceId),candidate,Long.toString(userId),
                Long.toString(clock.millis()),Integer.toString(config.getManagedCapacity()),
                Long.toString(config.getBacklogStopSeconds()*1000L),Integer.toString(config.getUnpersistedCapacity()),store.encode(frozen),
                Integer.toString(config.getDedupRetentionSeconds()),instanceNo,config.isAccepting() ? "1" : "0",Integer.toString(config.getPostedDedupSeconds()));
        String taskNo = reserved.get(0).toString(); long generation = Long.parseLong(reserved.get(1).toString());
        String issued = store.value("request-event:"+request);
        if (issued == null) issued = store.field("issued-events",taskNo);
        ComputeV2Event event;
        if (issued != null) event = store.decode(issued,ComputeV2Event.class);
        else {
            TaskSnapshot original = store.decode(store.field("snapshots",taskNo),TaskSnapshot.class);
            event = event(Kind.ISSUED,userId,deviceId,instanceNo,taskNo,generation,original.issuedAt(),original,null,null);
            // Two same-request writers construct exactly the same envelope from the first frozen snapshot.
            store.field("issued-events",taskNo,store.encode(event));
            store.cache("request-event:"+request,store.encode(event));
        }
        return store.persisted(event.eventId()) || store.field("posted-requests",event.taskNo()) != null ? event : send(event);
    }
    private ComputeV2Event historicalIssue(long userId,long deviceId,String requestId) {
        var original = persistence == null ? Optional.<ComputeV2Event>empty() : persistence.findIssued(userId,deviceId,requestId);
        var event = original.orElseThrow(() -> new IllegalStateException("COMPUTE_V2_REQUEST_HISTORY_UNAVAILABLE"));
        if (event.kind()!=Kind.ISSUED || event.userId()!=userId || event.deviceId()!=deviceId || !event.task().requestId().equals(requestId)
                || !event.deploymentScope().equals(config.getDeploymentScope())) throw new IllegalStateException("COMPUTE_V2_REQUEST_HISTORY_CONFLICT");
        return event;
    }

    public Optional<ComputeV2Event> acquireExecution(long userId, long deviceId, String executorId) {
        return reserveExecution(userId,deviceId,executorId).map(this::confirmExecution);
    }

    /** Runs under the shared short resourceGuard transaction; contains Redis work only, never MQ or inference. */
    public Optional<ComputeV2Event> reserveExecution(long userId,long deviceId,String executorId) {
        if (executorId == null || !executorId.matches("[A-Za-z0-9._:-]{1,128}")) throw new IllegalArgumentException("Invalid executor");
        RuntimeView view = view(userId,deviceId).orElseThrow(); ComputeV2Event current = view.event();
        Instant now = clock.instant(); Instant lease = now.plusSeconds(config.getLeaseSeconds());
        TaskSnapshot started = started(current.task(),now,lease,now.plusSeconds(current.task().executionTimeoutSec()+120L));
        ComputeV2Event event = event(Kind.STARTED,userId,deviceId,current.instanceNo(),current.taskNo(),current.generation(),now,started,null,null);
        String reserved = store.execute("start",current.taskNo(),Long.toString(current.generation()),executorId,
                Long.toString(lease.toEpochMilli()),store.encode(event),Integer.toString(config.getExecutionCapacity()),
                Long.toString(clock.millis()),Long.toString(config.getBacklogStopSeconds()*1000L),Integer.toString(config.getUnpersistedCapacity()),config.isAccepting() ? "1" : "0");
        if (reserved == null || reserved.isEmpty()) return Optional.empty();
        return Optional.of(store.decode(reserved,ComputeV2Event.class));
    }

    /** Sends the exact reserved envelope after the resourceGuard SQL transaction has ended. */
    public ComputeV2Event confirmExecution(ComputeV2Event reserved) {
        if (reserved.kind()!=Kind.STARTED) throw new IllegalArgumentException("Reserved STARTED event required");
        return send(reserved);
    }
    public int activeExecutionCount() { return Math.toIntExact(store.setSize("permits")); }

    public ComputeV2Event renew(long userId, long deviceId, long generation, String executorId) {
        var current = require(userId,deviceId,generation);
        store.execute("renew",current.taskNo(),Long.toString(generation),executorId,Long.toString(clock.millis()),
                Long.toString(clock.instant().plusSeconds(config.getLeaseSeconds()).toEpochMilli()));
        return current;
    }

    public ComputeV2Event verifiedComplete(long userId, long deviceId, long generation, Verified evidence) {
        var current = require(userId,deviceId,generation);
        String id = ComputeV2Event.eventId(current.taskNo(),generation,Kind.VERIFIED);
        String existing = store.field("completed",current.taskNo());
        if (existing != null) return sameProof(store.decode(existing,ComputeV2Event.class),evidence);
        String staged = store.field("staged",id);
        if (staged != null) return send(sameProof(store.decode(staged,ComputeV2Event.class),evidence));
        ComputeV2Event event = event(Kind.VERIFIED,userId,deviceId,current.instanceNo(),current.taskNo(),generation,
                clock.instant(),current.task(),evidence,null);
        return stageAndSend(event);
    }
    public boolean beginVerification(long userId,long deviceId,long generation,String submissionId) {
        if (submissionId == null || !submissionId.matches("[a-f0-9]{64}")) throw new IllegalArgumentException("Submission SHA-256 required");
        var current = require(userId,deviceId,generation);
        return "1".equals(store.execute("verify",current.taskNo(),Long.toString(generation),submissionId,Long.toString(clock.millis())));
    }
    public ComputeV2Event failVerification(long userId,long deviceId,long generation,String submissionId,String reason) {
        var current = require(userId,deviceId,generation);
        if (!Objects.equals(submissionId,store.field("verification-id",current.taskNo())))
            throw new IllegalArgumentException("Verification owner mismatch");
        return fail(userId,deviceId,generation,reason);
    }

    public ComputeV2Event fail(long userId,long deviceId,long generation,String reason) {
        var current = require(userId,deviceId,generation);
        return stageAndSend(event(Kind.FAILED,userId,deviceId,current.instanceNo(),current.taskNo(),generation,
                clock.instant(),current.task(),null,reason));
    }
    public boolean cancelQueued(long userId,long deviceId,long generation,String reason) {
        var current = require(userId,deviceId,generation);
        var failed = event(Kind.FAILED,userId,deviceId,current.instanceNo(),current.taskNo(),generation,
                clock.instant(),current.task(),null,reason);
        String original = store.execute("cancel",current.taskNo(),Long.toString(generation),failed.eventId(),store.encode(failed));
        if (original == null || original.isEmpty()) return false;
        send(store.decode(original,ComputeV2Event.class)); return true;
    }
    public ComputeV2Event fenceExpired(long userId,long deviceId,long generation) {
        var current = require(userId,deviceId,generation);
        return stageAndSend(event(Kind.FENCED,userId,deviceId,current.instanceNo(),current.taskNo(),generation,
                clock.instant(),current.task(),null,"LEASE_EXPIRED"));
    }
    /** Called only by the trusted process observer after the inference process actually exits. */
    public ComputeV2Event executionExited(long userId,long deviceId,long generation,String executorId) {
        var current = require(userId,deviceId,generation);
        String owner = store.field("executors",current.taskNo());
        if (owner != null && !owner.equals(executorId)) throw new IllegalStateException("COMPUTE_V2_LEASE_OWNED");
        return stageAndSend(event(Kind.EXITED,userId,deviceId,current.instanceNo(),current.taskNo(),generation,
                clock.instant(),current.task(),null,"PROCESS_EXIT_CONFIRMED"));
    }
    /** Only the trusted host observer may call this after signed evidence proves both processes have exited. */
    public ComputeV2Event executionObservedExit(long userId,long deviceId,long generation,String evidenceRef) {
        var current = require(userId,deviceId,generation);
        String prefix = "compute-v2/"+config.getDeploymentScope()+"/recovery/"+current.taskNo()+"/";
        if (evidenceRef == null || !evidenceRef.startsWith(prefix)
                || !evidenceRef.substring(prefix.length()).matches("[a-f0-9]{64}\\.json"))
            throw new IllegalArgumentException("Canonical process exit evidence required");
        return stageAndSend(event(Kind.EXITED,userId,deviceId,current.instanceNo(),current.taskNo(),generation,
                clock.instant(),current.task(),null,evidenceRef));
    }

    public Optional<RuntimeView> view(long userId,long deviceId) {
        String taskNo = store.field("devices",Long.toString(deviceId));
        if (taskNo == null) return Optional.empty();
        if (!Long.toString(userId).equals(store.field("owners",taskNo))) throw new IllegalArgumentException("Compute owner mismatch");
        String serialized = store.field("completed",taskNo);
        if (serialized == null) serialized = store.field("start-events",taskNo);
        if (serialized == null) serialized = store.field("issued-events",taskNo);
        if (serialized == null) {
            var task = store.decode(store.field("snapshots",taskNo),TaskSnapshot.class);
            serialized = store.encode(event(Kind.ISSUED,userId,deviceId,store.field("instances",taskNo),taskNo,
                    Long.parseLong(store.field("generation",taskNo)),task.issuedAt(),task,null,null));
        }
        Double lease = store.score("leases",taskNo);
        return Optional.of(new RuntimeView(store.decode(serialized,ComputeV2Event.class),store.field("states",taskNo),
                store.field("executors",taskNo),lease == null ? null : Instant.ofEpochMilli(lease.longValue()),
                amount("pending-usdt",userId),amount("pending-nex",userId)));
    }
    /** Internal scheduler lookup. Public callers must always use the authenticated-owner overload. */
    public Optional<RuntimeView> viewDevice(long deviceId) {
        String taskNo = store.field("devices",Long.toString(deviceId));
        if (taskNo == null) return Optional.empty();
        String owner = store.field("owners",taskNo);
        return owner == null ? Optional.empty() : view(Long.parseLong(owner),deviceId);
    }
    public List<String> queuedTasks(int limit) {
        return store.sorted("queue").stream().limit(Math.min(limit,config.getManagedCapacity())).toList();
    }
    public List<RuntimeView> queued(int limit) {
        return queuedTasks(limit).stream().map(task -> store.field("issued-events",task)).filter(Objects::nonNull)
                .map(value -> store.decode(value,ComputeV2Event.class)).map(event -> view(event.userId(),event.deviceId()).orElseThrow()).toList();
    }
    public PendingSummary pendingSummary(long userId) {
        return pendingVerifiedSnapshot(userId).summary();
    }
    public List<ComputeV2Event> pendingEvents(long userId) { return pendingVerifiedSnapshot(userId).events(); }
    public PendingVerifiedSnapshot pendingVerifiedSnapshot(long userId) {
        if (userId <= 0) throw new IllegalArgumentException("Invalid owner");
        var values = store.reserve("summary",Long.toString(userId));
        var summary = new PendingSummary(Long.parseLong(values.get(0).toString()),Long.parseLong(values.get(1).toString()),
                Long.parseLong(values.get(2).toString()),Long.parseLong(values.get(3).toString()));
        var events = values.subList(4,values.size()).stream().map(value -> store.decode(value.toString(),ComputeV2Event.class)).toList();
        return new PendingVerifiedSnapshot(summary,events);
    }
    public void retire(long userId,long deviceId) {
        view(userId,deviceId); store.execute("retire",Long.toString(deviceId),Integer.toString(config.getPostedDedupSeconds()));
    }
    public Optional<ComputeV2Event> event(String taskNo) {
        String value = store.field("completed",taskNo);
        if (value == null) value = store.field("start-events",taskNo);
        if (value == null) value = store.field("issued-events",taskNo);
        return value == null ? Optional.empty() : Optional.of(store.decode(value,ComputeV2Event.class));
    }
    public boolean hasExited(String taskNo,long generation) {
        return store.field("lifecycle-events",ComputeV2Event.eventId(taskNo,generation,Kind.EXITED)) != null;
    }
    public boolean completionPending(String taskNo,long generation) {
        String id = ComputeV2Event.eventId(taskNo,generation,Kind.VERIFIED);
        return store.field("staged",id) != null || store.field("outgoing",id) != null;
    }
    public boolean isReady() { return "1".equals(store.execute("ready")); }
    public boolean isAccepting() { return config.isAccepting(); }
    public boolean canAcceptWork() {
        return "1".equals(store.execute("admission",Long.toString(clock.millis()),Long.toString(config.getBacklogStopSeconds()*1000L),
                Integer.toString(config.getUnpersistedCapacity()),config.isAccepting() ? "1" : "0"));
    }
    public Optional<String> settlementBlock(String taskNo,long generation) {
        return Optional.ofNullable(store.field("blocked-events",ComputeV2Event.eventId(taskNo,generation,Kind.VERIFIED)));
    }
    public Optional<RuntimeView> forExecutor(String executorId) {
        for (String permit : store.members("permits")) {
            String taskNo = permit.substring(0,permit.lastIndexOf(':'));
            if (executorId.equals(store.field("executors",taskNo))) {
                long user = Long.parseLong(store.field("owners",taskNo));
                long device = Long.parseLong(store.field("task-devices",taskNo));
                return view(user,device);
            }
        }
        return Optional.empty();
    }
    private long amount(String name,long userId) { String value = store.field(name,Long.toString(userId)); return value == null ? 0 : Long.parseLong(value); }
    private ComputeV2Event require(long userId,long deviceId,long generation) {
        var event = view(userId,deviceId).orElseThrow().event();
        if (event.generation() != generation) throw new IllegalStateException("COMPUTE_V2_STALE_GENERATION");
        return event;
    }
    private ComputeV2Event stageAndSend(ComputeV2Event event) {
        String entitlement = "", zeroReward = "";
        if (event.evidence() != null && event.evidence().rewardNexMicros() > 0) {
            var e = event.evidence(); entitlement = e.dailyEntitlementKey();
            var zero = new Verified(e.proofHash(),e.inputSha256(),e.resultSha256(),e.verifiedAt(),e.completedAt(),
                    e.artifactRef(),e.artifactBytes(),0,null,e.utcDay());
            zeroReward = store.encode(event(Kind.VERIFIED,event.userId(),event.deviceId(),event.instanceNo(),event.taskNo(),
                    event.generation(),event.occurredAt(),event.task(),zero,null));
        }
        String original = store.execute("stage",event.taskNo(),Long.toString(event.generation()),event.eventId(),store.encode(event),
                event.kind().name(),Long.toString(clock.millis()),entitlement,zeroReward,Integer.toString(config.getDedupRetentionSeconds()));
        return send(store.decode(original,ComputeV2Event.class));
    }
    private ComputeV2Event sameProof(ComputeV2Event original,Verified evidence) {
        var saved = original.evidence();
        if (!saved.proofHash().equals(evidence.proofHash()) || !saved.resultSha256().equals(evidence.resultSha256())
                || !saved.inputSha256().equals(evidence.inputSha256()) || !saved.artifactRef().equals(evidence.artifactRef()))
            throw new IllegalStateException("COMPUTE_V2_COMPLETION_CONFLICT");
        return original;
    }
    private ComputeV2Event send(ComputeV2Event event) {
        String original = store.execute("outgoing",event.eventId(),store.encode(event),Long.toString(clock.millis()),Integer.toString(config.getUnpersistedCapacity()));
        event = store.decode(original,ComputeV2Event.class);
        log.send(event); // Unknown sends throw; caller receives no completion acknowledgement and retries this event ID.
        store.apply(event,clock.instant());
        return event;
    }
    private ComputeV2Event event(Kind kind,long userId,long deviceId,String instanceNo,String taskNo,long gen,Instant at,
            TaskSnapshot task,Verified verified,String reason) {
        return new ComputeV2Event(2,ComputeV2Event.eventId(taskNo,gen,kind),kind,config.getDeploymentScope(),"PRODUCTION","",
                userId,deviceId,instanceNo,taskNo,gen,at,task,verified,reason);
    }
    public static TaskSnapshot started(TaskSnapshot t,Instant at,Instant lease,Instant proof) {
        return new TaskSnapshot(t.requestId(),t.taskConfigId(),t.taskName(),t.taskClass(),t.modelName(),t.clientName(),
                t.configVersion(),t.expectedSeconds(),t.minSeconds(),t.maxSeconds(),t.requiredSeconds(),t.executionTimeoutSec(),t.taskLockMinutes(),
                t.issuedAt(),at,lease,proof,t.rewardUsdtMicros(),t.rawQuoteMicros(),t.e3Multiplier(),t.walletId(),
                t.walletIncarnation(),t.device(),t.inputSha256(),t.modelSha256(),t.inputArtifactRef());
    }
}
