package ffdd.opsconsole.device.application;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import ffdd.opsconsole.device.application.TestCloudExecutorService.Identity;
import ffdd.opsconsole.device.compute.v2.*;
import ffdd.opsconsole.device.compute.v2.ComputeV2Event.*;
import ffdd.opsconsole.device.domain.TaskSchedulingSettings;
import ffdd.opsconsole.device.mapper.AppTaskAssignmentMapper;
import ffdd.opsconsole.device.mapper.TaskSchedulingPolicyMapper;
import ffdd.opsconsole.device.mapper.ComputeV2AdmissionMapper;
import ffdd.opsconsole.shared.capacity.E3DeviceCapacityPolicy;
import ffdd.opsconsole.shared.config.DateTimeFormatConfig;
import ffdd.opsconsole.shared.exception.BizException;
import ffdd.opsconsole.shared.storage.ObjectStorageService;
import java.io.ByteArrayInputStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.Semaphore;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicInteger;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.env.Environment;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.beans.factory.annotation.Autowired;

/** TEST-only bridge. DB authority is short-lived; inference/MinIO/MQ never hold a SQL connection. */
@Service
@ConditionalOnProperty(name="nexion.compute-v2.enabled",havingValue="true")
public class TestComputeV2Service {
    private static final Logger LOG=LoggerFactory.getLogger(TestComputeV2Service.class);
    private final ComputeV2Runtime runtime;
    private final TestCloudExecutorService cloud;
    private final TestCloudRequests transactions;
    private final AppTaskAssignmentMapper assignments;
    private final TaskSchedulingPolicyMapper policies;
    private final ObjectStorageService objects;
    private final ComputeV2AdmissionMapper admission;
    private final TestCloudActivationAdmission physicalAdmission;
    private final Environment environment;
    private final ObjectMapper json;
    private final Clock clock;
    private final Set<Long> enrolled;
    private final Set<String> allowedTasks;
    private final byte[] proofSecret;
    private final Map<String,String> runnerContainers;
    @SuppressWarnings("ArchitectureConfigField") // Validated from constructor-injected deployment configuration.
    private final String validatorContainer;
    private final Set<String> activeValidations=ConcurrentHashMap.newKeySet();
    private final Queue<Long> readyDevices=new ConcurrentLinkedQueue<>();
    private final Set<Long> readyDeviceIds=ConcurrentHashMap.newKeySet();
    private final SecureRandom random=new SecureRandom();
    private final Semaphore validator=new Semaphore(1,true),validationCapacity=new Semaphore(13,true);
    private final Optional<ComputeV2DeviceLifecycleService> lifecycle;
    private final AtomicInteger scanCursor=new AtomicInteger();

    @Autowired
    public TestComputeV2Service(ComputeV2Runtime runtime,TestCloudExecutorService cloud,TestCloudRequests transactions,
            AppTaskAssignmentMapper assignments,TaskSchedulingPolicyMapper policies,ObjectStorageService objects,
            ComputeV2AdmissionMapper admission,Environment environment,ObjectMapper json,Clock clock,
            Optional<ComputeV2DeviceLifecycleService> lifecycle,TestCloudActivationAdmission physicalAdmission) {
        this.runtime=runtime;this.cloud=cloud;this.transactions=transactions;this.assignments=assignments;
        this.policies=policies;this.objects=objects;this.admission=admission;this.lifecycle=lifecycle;this.physicalAdmission=physicalAdmission;
        this.environment=environment;this.json=json;this.clock=clock;
        if(!"TEST".equals(environment.getProperty("nexion.compute-v2.deployment-scope")))
            throw new IllegalStateException("COMPUTE_V2_TEST_ADAPTER_SCOPE_REQUIRED");
        enrolled=ids(environment.getProperty("nexion.compute-v2.device-ids",""));
        allowedTasks=Set.copyOf(Arrays.stream(environment.getProperty("nexion.compute-v2.allowed-task-ids","").split(","))
                .map(String::trim).filter(s->!s.isEmpty()).toList());
        String secret=environment.getProperty("nexion.compute-v2.proof-secret","");
        if(!secret.matches("[a-f0-9]{64}") || allowedTasks.isEmpty())
            throw new IllegalStateException("COMPUTE_V2_EXECUTOR_CAPABILITY_CONFIGURATION_REQUIRED");
        proofSecret=HexFormat.of().parseHex(secret);
        var containers=new HashMap<String,String>();
        for(int n=1;n<=6;n++) {
            String id=environment.getProperty("nexion.compute-v2.runner-"+n+"-container-id","");
            if(!id.matches("[a-f0-9]{64}")) throw new IllegalStateException("COMPUTE_V2_RUNNER_CONTAINER_REQUIRED");
            containers.put("runner-"+n,id);
        }
        validatorContainer=environment.getProperty("nexion.compute-v2.validator-container-id","");
        if(!validatorContainer.matches("[a-f0-9]{64}") || new HashSet<>(containers.values()).size()!=6 || containers.containsValue(validatorContainer))
            throw new IllegalStateException("COMPUTE_V2_EXCLUSIVE_MODEL_CONTAINERS_REQUIRED");
        runnerContainers=Map.copyOf(containers);
    }

    public record Complete(long generation,String claimToken,String proofNonce,String inputHash,String modelDigest,
            String resultHash,String resultArtifactBase64,long proofTimestamp) { }
    record Manifest(int version,String runtimeKey,String validatorKey,String taskConfigId,long revision,
            String model,String modelDigest,int executionTimeoutSec,List<String> texts) { }
    record Issuance(Identity identity,TaskSnapshot snapshot,byte[] manifest) { }

    public synchronized Map<String,Object> next(Identity caller,String runner) {
        if(org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive())
            throw new IllegalStateException("COMPUTE_V2_DISPATCH_OUTER_TRANSACTION");
        requireFleet(caller);String executor=executor(runner);
        var original=runtime.forExecutor(executor);
        if(original.isPresent()) {
            var view=original.get();var event=view.event();
            if(Set.of("STARTING","COMPUTING","VERIFYING").contains(view.status()) && view.leaseExpiresAt()!=null
                    && !view.leaseExpiresAt().isAfter(clock.instant()) && !runtime.completionPending(event.taskNo(),event.generation())) {
                runtime.fenceExpired(event.userId(),event.deviceId(),event.generation());
                view=runtime.view(event.userId(),event.deviceId()).orElseThrow();
            }
            if("STARTING".equals(view.status())) {
                // A lost commit/send acknowledgement retries the immutable reservation without borrowing another permit.
                runtime.confirmExecution(view.event());
                view=runtime.view(event.userId(),event.deviceId()).orElseThrow();
            }
            return manifest(view);
        }
        if(!runtime.canAcceptWork())return Map.of("state","IDLE","retryAfterMs",1000);
        // Single global head is consumed fairly. Ineligible heads terminate without consuming a compute permit.
        for(var view:runtime.queued(12)) {
            var event=view.event();
            Optional<ComputeV2Event> acquired;
            try { acquired=transactions.db(()->{
                // Serialize against legacy activation before checking ownership or reserving physical compute.
                physicalAdmission.lock();
                if(!physicalAdmission.canStartV2Execution(runtime.activeExecutionCount())) return Optional.empty();
                eligible(caller,event.deviceId(),event.userId(),false);
                return runtime.reserveExecution(event.userId(),event.deviceId(),executor);
            }); }
            catch(BizException invalid) {
                if(invalid.getCode()!=409 && invalid.getCode()!=403 && invalid.getCode()!=404) throw invalid;
                runtime.fail(event.userId(),event.deviceId(),event.generation(),"ADMISSION_REVOKED");
                runtime.retire(event.userId(),event.deviceId());continue;
            }
            if(acquired.isEmpty()) break;
            runtime.confirmExecution(acquired.get());
            return manifest(runtime.view(event.userId(),event.deviceId()).orElseThrow());
        }
        return Map.of("state","IDLE","retryAfterMs",1000);
    }

    public Map<String,Object> heartbeat(Identity caller,String runner,String taskNo,long generation,String token) {
        var view=ownedExecution(caller,runner,taskNo,generation,token);
        runtime.renew(view.event().userId(),view.event().deviceId(),generation,executor(runner));
        return Map.of("state",view.status(),"serverNow",clock.instant().toString());
    }

    public Map<String,Object> complete(Identity caller,String runner,String taskNo,Complete request) {
        if(org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive())
            throw new IllegalStateException("COMPUTE_V2_INFERENCE_OUTER_TRANSACTION");
        var view=ownedExecution(caller,runner,taskNo,request.generation(),request.claimToken());
        var event=view.event();
        if(event.kind()==Kind.VERIFIED) {
            if(!event.evidence().resultSha256().equals(request.resultHash()) || !event.task().inputSha256().equals(request.inputHash())
                    || !("sha256:"+event.task().modelSha256()).equals(request.modelDigest())
                    || !constant(request.proofNonce(),signature(event,"nonce"))) throw error(409,"COMPUTE_V2_PROOF_CONFLICT");
            return manifest(view);
        }
        if("VERIFYING".equals(view.status())) {
            validateProof(view,request,false);
            if(activeValidations.contains(taskNo) || runtime.completionPending(taskNo,request.generation()))
                throw error(503,"COMPUTE_V2_VALIDATION_IN_PROGRESS");
            throw error(409,"COMPUTE_V2_VALIDATION_RESTART_REQUIRES_RECOVERY");
        }
        validateProof(view,request,true);
        Instant completedAt=clock.instant();
        TestCloudBgeArtifact.decode(request.resultArtifactBase64(),request.resultHash());
        String submission=TestCloudExecutorService.sha256(taskNo+":"+request.generation()+":"+request.resultHash());
        if(!validationCapacity.tryAcquire()) throw error(429,"COMPUTE_V2_VALIDATION_QUEUE_FULL");
        boolean acquired=false,verifying=false,validationRegistered=false;
        try {
            validationRegistered=activeValidations.add(taskNo);
            if(!validationRegistered)throw error(503,"COMPUTE_V2_VALIDATION_IN_PROGRESS");
            if(!runtime.beginVerification(event.userId(),event.deviceId(),event.generation(),submission))
                throw error(503,"COMPUTE_V2_VALIDATION_IN_PROGRESS");
            verifying=true;
            validator.acquire();acquired=true;
            validateProof(ownedExecution(caller,runner,taskNo,request.generation(),request.claimToken()),request,false);
            Manifest input=input(event);
            Identity device=cloud.computeV2Identity(caller,event.deviceId());
            byte[] bytes=TestCloudBgeArtifact.verify(request.resultArtifactBase64(),request.resultHash(),cloud.infer(device,input.texts()));
            // Recheck mutable ownership after expensive proof verification; never redraw the task quote.
            long nex=transactions.db(()->{
                eligible(caller,event.deviceId(),event.userId(),true);
                var wallet=wallet(event.userId());
                if(((Number)wallet.get("id")).longValue()!=event.task().walletId()
                        || !event.task().walletIncarnation().equals(wallet.get("incarnation")))
                    throw error(409,"COMPUTE_V2_WALLET_IDENTITY_CHANGED");
                BigDecimal amount=assignments.lockPaidCloudShareDailyNex(event.userId(),event.deviceId());
                String dailyKey="CLOUD_SHARE_DAILY:"+event.deviceId()+":"+completedAt.atZone(ZoneOffset.UTC).toLocalDate();
                if(assignments.lockDailyCloudShareNex(event.userId(),dailyKey)!=null) return 0L;
                return amount==null?0L:ComputeV2Selection.micros(amount);
            });
            var current=ownedExecution(caller,runner,taskNo,event.generation(),request.claimToken());
            if(current.leaseExpiresAt()==null || !current.leaseExpiresAt().isAfter(clock.instant()))
                throw error(409,"COMPUTE_V2_LEASE_EXPIRED");
            Instant completed=completedAt;
            String artifact="compute-v2/TEST/result/"+event.userId()+"/"+taskNo+"/"+request.resultHash()+".f32";
            objects.put(artifact,"application/octet-stream",new ByteArrayInputStream(bytes),bytes.length);
            LocalDate day=completed.atZone(ZoneOffset.UTC).toLocalDate();
            String proof=TestCloudExecutorService.sha256(taskNo+":"+event.generation()+":"+event.task().inputSha256()+":"+request.resultHash()+":"+request.proofNonce());
            runtime.verifiedComplete(event.userId(),event.deviceId(),event.generation(),new Verified(proof,
                    event.task().inputSha256(),request.resultHash(),clock.instant(),completed,artifact,bytes.length,nex,
                    nex>0?"CLOUD_SHARE_DAILY:"+event.deviceId()+":"+day:null,day));
            return manifest(runtime.view(event.userId(),event.deviceId()).orElseThrow());
        } catch(InterruptedException interrupted) {
            Thread.currentThread().interrupt();throw error(503,"COMPUTE_V2_VALIDATION_INTERRUPTED");
        } catch(RuntimeException failure) {
            // Unknown MQ send already has a staged immutable VERIFIED event; fencing must not discard it.
            if(verifying && failure instanceof BizException)
                runtime.failVerification(event.userId(),event.deviceId(),event.generation(),submission,"VALIDATION_REQUIRES_RECOVERY");
            throw failure;
        } finally {
            if(validationRegistered) activeValidations.remove(taskNo);
            if(acquired) validator.release();validationCapacity.release();
        }
    }

    /** Root observer signs physical exit evidence; workers have claim tokens but never the signing key. */
    public Map<String,Object> observedExit(Identity caller,String runner,String taskNo,long generation,String claim,
            JsonNode observation,String signed) {
        requireFleet(caller);executor(runner);
        Set<String> expected=Set.of("taskNo","generation","runner","observedAt","computeContainerId","validatorContainerId","computeExitCode","validatorExitCode");
        Set<String> keys=new HashSet<>();observation.fieldNames().forEachRemaining(keys::add);
        if(!observation.isObject() || !keys.equals(expected)) throw error(422,"COMPUTE_V2_EXIT_EVIDENCE_INVALID");
        var values=new TreeMap<String,Object>();observation.fields().forEachRemaining(field->{
            JsonNode value=field.getValue();if(value.isTextual())values.put(field.getKey(),value.textValue());
            else if(value.isIntegralNumber()&&value.canConvertToLong())values.put(field.getKey(),value.longValue());
            else throw error(422,"COMPUTE_V2_EXIT_EVIDENCE_INVALID");});
        byte[] bytes=encode(values);
        if(!constant(signed,hmac(bytes)) || !taskNo.equals(observation.path("taskNo").asText())
                || generation!=observation.path("generation").asLong(-1) || !runner.equals(observation.path("runner").asText())
                || !runnerContainers.get(runner).equals(observation.path("computeContainerId").asText())
                || !validatorContainer.equals(observation.path("validatorContainerId").asText())
                || !observation.path("computeExitCode").isInt() || !observation.path("validatorExitCode").isInt())
            throw error(403,"COMPUTE_V2_EXIT_EVIDENCE_INVALID");
        var event=retained(taskNo);
        if(event.generation()!=generation || !constant(claim,signature(event,"claim"))) throw error(403,"COMPUTE_V2_PROOF_BINDING_INVALID");
        Instant observed;
        try{observed=Instant.parse(observation.path("observedAt").textValue());}catch(RuntimeException malformed){throw error(422,"COMPUTE_V2_EXIT_EVIDENCE_INVALID");}
        if(event.task().startedAt()==null || observed.isBefore(event.task().startedAt())
                || observed.isAfter(clock.instant().plusSeconds(5)) || observed.isBefore(clock.instant().minusSeconds(300)))
            throw error(409,"COMPUTE_V2_EXIT_EVIDENCE_STALE");
        if(runtime.hasExited(taskNo,generation)){afterExit(event);return Map.of("taskNo",taskNo,"state","EXIT_CONFIRMED");}
        var view=runtime.view(event.userId(),event.deviceId()).orElseThrow(()->error(409,"COMPUTE_V2_GENERATION_NOT_CURRENT"));
        if(!view.event().taskNo().equals(taskNo) || activeValidations.contains(taskNo)) throw error(409,"COMPUTE_V2_VALIDATOR_STILL_ACTIVE");
        if(Set.of("COMPUTING","VERIFYING","STARTING").contains(view.status()))
            runtime.fenceExpired(event.userId(),event.deviceId(),generation);
        else if(!Set.of("FAILED","RECOVERING","COMPLETED").contains(view.status())) throw error(409,"COMPUTE_V2_NOT_TERMINAL");
        String ref="compute-v2/TEST/recovery/"+taskNo+"/"+TestCloudExecutorService.sha256(bytes)+".json";
        objects.put(ref,"application/json",new ByteArrayInputStream(bytes),bytes.length);
        runtime.executionObservedExit(event.userId(),event.deviceId(),generation,ref);
        afterExit(event);
        return Map.of("taskNo",taskNo,"state","EXIT_CONFIRMED","evidenceRef",ref);
    }

    /** The bounded worker calls this only after both successful inference responses and durable VERIFIED acknowledgement. */
    public Map<String,Object> exited(Identity caller,String runner,String taskNo,long generation,String token) {
        requireFleet(caller);executor(runner);
        var retained=retained(taskNo);
        if(retained.generation()!=generation || !constant(token,signature(retained,"claim"))) throw error(403,"COMPUTE_V2_PROOF_BINDING_INVALID");
        if(runtime.hasExited(taskNo,generation) || admission.exitCount(taskNo,generation)>0){
            afterExit(retained);return Map.of("taskNo",taskNo,"state","EXIT_CONFIRMED");}
        var view=ownedExecution(caller,runner,taskNo,generation,token);
        if(view.event().kind()!=Kind.VERIFIED) throw error(409,"COMPUTE_V2_EXIT_REQUIRES_VERIFIED_RESULT");
        runtime.executionExited(view.event().userId(),view.event().deviceId(),generation,executor(runner));
        afterExit(view.event());
        return Map.of("taskNo",taskNo,"state","EXIT_CONFIRMED");
    }

    public Map<String,Object> receipt(Identity caller,String runner,String taskNo,long generation,String token) {
        requireFleet(caller);executor(runner);
        var event=retained(taskNo);
        if(event.generation()!=generation || !constant(token,signature(event,"claim"))) throw error(403,"COMPUTE_V2_PROOF_BINDING_INVALID");
        Map<String,Object> result=new LinkedHashMap<>();result.put("taskNo",taskNo);
        result.put("generation",event.generation());result.put("inputHash",event.task().inputSha256());
        result.put("startedAt",event.task().startedAt()==null?null:event.task().startedAt().toString());
        boolean exited=runtime.hasExited(taskNo,generation) || admission.exitCount(taskNo,generation)>0;
        result.put("state",event.kind()==Kind.VERIFIED?"COMPLETED":exited?"FAILED":"IN_PROGRESS");result.put("exitConfirmed",exited);
        if(event.evidence()!=null) {result.put("resultHash",event.evidence().resultSha256());result.put("receiptNo",event.receiptNo());}
        return result;
    }

    private ComputeV2Event retained(String taskNo) {
        return runtime.event(taskNo).orElseGet(()->{
            var payload=admission.retainedPayload(taskNo);
            if(payload==null) throw error(404,"COMPUTE_V2_RECEIPT_NOT_FOUND");
            try {return json.readValue(payload,ComputeV2Event.class);}
            catch(java.io.IOException malformed){throw new IllegalStateException("COMPUTE_V2_EVENT_INVALID",malformed);}
        });
    }

    @Scheduled(fixedDelayString="${nexion.compute-v2.dispatch-delay-ms:1000}",scheduler="computeV2TaskScheduler")
    public synchronized void issueEnrolled() {
        if(enrolled.isEmpty() || !runtime.isReady()) return;
        lifecycle.ifPresent(ComputeV2DeviceLifecycleService::finalizePendingEnrolled);
        if(!runtime.canAcceptWork())return;
        List<Long> devices=enrolled.stream().sorted().toList();
        // One shared cursor, bounded work per tick; no account-specific timer, JVM or journal.
        for(int n=0;n<Math.min(12,devices.size());n++) {
            Long priority=n<6?readyDevices.poll():null;
            if(priority!=null)readyDeviceIds.remove(priority);
            long deviceId=priority==null?devices.get(Math.floorMod(scanCursor.getAndIncrement(),devices.size())):priority;
            try {
                var current=runtime.viewDevice(deviceId);
                if(current.isPresent()) {
                    var view=current.get();var event=view.event();
                    if("ISSUING".equals(view.status())) {
                        runtime.issue(event.userId(),deviceId,event.instanceNo(),event.task());continue;
                    }
                    if(!Set.of("COMPLETED","FAILED").contains(view.status()) || view.executorId()!=null)continue;
                    runtime.retire(event.userId(),deviceId);
                }
                Issuance issuance=transactions.db(()->freeze(deviceId));
                if(issuance==null) continue;
                String object=issuance.snapshot().inputArtifactRef();
                if(issuance.manifest().length>0)
                    objects.put(object,"application/json",new ByteArrayInputStream(issuance.manifest()),issuance.manifest().length);
                runtime.issue(issuance.identity().ownerId(),deviceId,issuance.identity().instanceNo(),issuance.snapshot());
            } catch(RuntimeException failure) {
                LOG.warn("Compute v2 admission deferred device={} reason={}",deviceId,failure.getClass().getSimpleName());
            }
        }
    }

    private Issuance freeze(long deviceId) {
        Identity fleet=new Identity("fleet",0,0,"fleet",environment.getProperty(TestCloudExecutorService.PREFIX+"task-config-id"),
                environment.getProperty(TestCloudExecutorService.PREFIX+"model"),environment.getProperty(TestCloudExecutorService.PREFIX+"model-digest"));
        Identity device=cloud.computeV2Identity(fleet,deviceId);
        var owned=eligible(fleet,deviceId,device.ownerId(),false);
        int routingVram=AppTaskAssignmentService.effectiveRoutingVram(owned);
        var candidates=new ArrayList<ComputeV2Selection.Candidate>();
        for(var task:assignments.eligibleTasks(routingVram)) {
            if(!allowedTasks.contains(task.taskId()) || !"EM".equals(task.taskClass()) || !"BGE-M3".equals(task.modelName())
                    || task.minVram()==null || task.minVram()>routingVram) continue;
            var saved=policies.find(task.taskId());
            var policy=saved==null?TaskSchedulingSettings.defaults(task.taskClass(),task.modelName()):saved.settings();
            if(!"test-bge-m3-v1".equals(policy.runtimeKey()) || !policy.runtimeKey().equals(policy.validatorKey())) continue;
            candidates.add(new ComputeV2Selection.Candidate(task.taskId(),task.name(),task.taskClass(),task.modelName(),
                    task.minReward(),task.maxReward(),saved==null?0:saved.revision(),policy,texts(policy.workloadConfigJson())));
        }
        var selected=ComputeV2Selection.draw(candidates,random);var task=selected.candidate();
        var capacity=new HashMap<String,String>();for(var row:assignments.e3CapacityConfig())capacity.put(row.configKey(),row.configValue());
        var projection=E3DeviceCapacityPolicy.project(owned.productCode(),owned.deviceType(),owned.purchasedAt(),owned.activatedAt(),
                LocalDateTime.ofInstant(clock.instant(),DateTimeFormatConfig.BUSINESS_ZONE),capacity);
        long reward=ComputeV2Selection.micros(E3DeviceCapacityPolicy.applyCapacity(BigDecimal.valueOf(selected.rawMicros(),6),projection));
        var input=new Manifest(2,task.settings().runtimeKey(),task.settings().validatorKey(),task.taskId(),task.revision(),
                device.model(),device.modelDigest(),task.settings().executionTimeoutSec(),task.texts());
        byte[] bytes=encode(input);String hash=TestCloudExecutorService.sha256(bytes);var wallet=wallet(device.ownerId());
        Instant now=clock.instant();
        var snapshot=new TaskSnapshot("CV2-NEXT-"+UUID.randomUUID(),task.taskId(),task.name(),task.taskClass(),TestCloudExecutorService.KIND,
                "UVEL Compute",task.revision(),selected.expectedSeconds(),task.settings().expectedDurationMinSec(),
                task.settings().expectedDurationMaxSec(),selected.expectedSeconds(),task.settings().executionTimeoutSec(),0,
                now,null,now.plusSeconds(45),now.plusSeconds(task.settings().executionTimeoutSec()+120L),reward,selected.rawMicros(),
                projection.capacityPct().movePointLeft(2),((Number)wallet.get("id")).longValue(),wallet.get("incarnation").toString(),
                new DeviceSnapshot(owned.name(),owned.deviceType(),owned.productCode(),null,null,null,owned.vramTotalGb()),hash,
                device.modelDigest().substring(7),"compute-v2/TEST/input/"+hash+".json");
        return new Issuance(device,snapshot,bytes);
    }

    private AppTaskAssignmentMapper.DeviceRow eligible(Identity caller,long deviceId,long userId,boolean completing) {
        if(!enrolled.contains(deviceId)) throw error(403,"COMPUTE_V2_DEVICE_NOT_ENROLLED");
        Identity identity=cloud.computeV2Identity(caller,deviceId);
        if(identity.ownerId()!=userId) throw error(403,"COMPUTE_V2_OWNER_CHANGED");
        cloud.requireComputeV2Paid(identity,completing);
        var device=assignments.lockOwnedDevice(userId,deviceId);
        if(device==null || !identity.instanceNo().equals(device.instanceNo())) throw error(409,"COMPUTE_V2_OWNER_CHANGED");
        Integer pending=admission.pendingDeactivate(deviceId,userId);
        if((!completing && (pending==null || pending!=0)) || device.activatedAt()==null
                || !Set.of("ACTIVE","ONLINE","BUSY","RUNNING").contains(device.status().toUpperCase(Locale.ROOT))
                || Boolean.TRUE.equals(device.dispatchPaused()) || device.pausedReason()!=null&&!device.pausedReason().isBlank())
            throw error(409,"COMPUTE_V2_DEVICE_NOT_ACTIVE");
        if(admission.unresolvedLegacy(userId,deviceId)!=0) throw error(409,"COMPUTE_V2_V1_NOT_DRAINED");
        long ownedEnrolled=assignments.ownedDevices(userId).stream().filter(row->enrolled.contains(row.id()) && row.activatedAt()!=null
                && Set.of("ACTIVE","ONLINE","BUSY","RUNNING","OFFLINE").contains(row.status().toUpperCase(Locale.ROOT))).count();
        if(ownedEnrolled>6) throw error(409,"COMPUTE_V2_ACCOUNT_SLOT_LIMIT");
        return device;
    }

    private Map<String,Object> wallet(long user) {
        var rows=admission.lockWallets(user);
        if(rows.size()!=1) throw error(409,"COMPUTE_V2_WALLET_UNAVAILABLE");return rows.get(0);
    }
    private ComputeV2Runtime.RuntimeView ownedExecution(Identity caller,String runner,String task,long generation,String token) {
        requireFleet(caller);
        var view=runtime.forExecutor(executor(runner)).orElseThrow(()->error(409,"COMPUTE_V2_EXECUTOR_NOT_ASSIGNED"));
        if(!view.event().taskNo().equals(task) || view.event().generation()!=generation
                || !constant(token,signature(view.event(),"claim"))) throw error(403,"COMPUTE_V2_PROOF_BINDING_INVALID");
        return view;
    }
    private void validateProof(ComputeV2Runtime.RuntimeView view,Complete proof,boolean received) {
        var event=view.event();Instant now=clock.instant();
        if(!Set.of("COMPUTING","VERIFYING").contains(view.status()) || view.leaseExpiresAt()==null || !view.leaseExpiresAt().isAfter(now)
                || received && (!event.task().proofExpiresAt().isAfter(now) || Math.abs(now.getEpochSecond()-proof.proofTimestamp())>30)
                || !event.task().inputSha256().equals(proof.inputHash()) || !("sha256:"+event.task().modelSha256()).equals(proof.modelDigest())
                || !constant(proof.proofNonce(),signature(event,"nonce"))) throw error(409,"COMPUTE_V2_PROOF_BINDING_INVALID");
    }
    private Map<String,Object> manifest(ComputeV2Runtime.RuntimeView view) {
        var e=view.event();var input=input(e);var result=new LinkedHashMap<String,Object>();
        result.put("state",view.status());result.put("protocolVersion",2);result.put("taskNo",e.taskNo());result.put("generation",e.generation());
        result.put("deviceId",e.deviceId());result.put("ownerId",e.userId());result.put("taskType",e.task().taskClass());
        result.put("startedAt",e.task().startedAt()==null?null:e.task().startedAt().toString());
        if(view.executorId()!=null && view.executorId().startsWith("compute-v2:"))
            result.put("computeContainerId",runnerContainers.get(view.executorId().substring("compute-v2:".length())));
        result.put("validatorContainerId",validatorContainer);
        result.put("taskConfigId",e.task().taskConfigId());result.put("configVersion",e.task().configVersion());result.put("expectedDurationSec",e.task().expectedSeconds());
        result.put("executionTimeoutSec",e.task().executionTimeoutSec());result.put("model",input.model());result.put("modelDigest",input.modelDigest());
        result.put("texts",input.texts());result.put("inputHash",e.task().inputSha256());result.put("claimToken",signature(e,"claim"));result.put("proofNonce",signature(e,"nonce"));
        if(e.evidence()!=null){result.put("resultHash",e.evidence().resultSha256());result.put("receiptNo",e.receiptNo());}
        return result;
    }
    private Manifest input(ComputeV2Event event) {
        try {var input=json.readValue(inputBytes(event),Manifest.class);
            if(input.version()!=2 || !"test-bge-m3-v1".equals(input.runtimeKey()) || !input.runtimeKey().equals(input.validatorKey())
                    || !input.taskConfigId().equals(event.task().taskConfigId()) || input.revision()!=event.task().configVersion()
                    || !input.modelDigest().equals("sha256:"+event.task().modelSha256()) || input.executionTimeoutSec()!=event.task().executionTimeoutSec())
                throw error(409,"COMPUTE_V2_INPUT_MANIFEST_INVALID");
            return input;
        } catch(java.io.IOException malformed){throw error(409,"COMPUTE_V2_INPUT_MANIFEST_INVALID");}
    }
    private byte[] inputBytes(ComputeV2Event event) {
        if(!event.task().inputArtifactRef().equals("compute-v2/TEST/input/"+event.task().inputSha256()+".json")) throw error(409,"COMPUTE_V2_INPUT_MANIFEST_INVALID");
        try(var stream=objects.get(event.task().inputArtifactRef())) {byte[] bytes=stream.readNBytes(20001);
            if(bytes.length>20000 || !event.task().inputSha256().equals(TestCloudExecutorService.sha256(bytes))) throw error(409,"COMPUTE_V2_INPUT_MANIFEST_INVALID");
            return bytes;
        } catch(java.io.IOException unavailable){throw error(503,"COMPUTE_V2_INPUT_UNAVAILABLE");}
    }
    private List<String> texts(String config) {
        try {JsonNode root=json.readTree(config);if(root.isEmpty()) return TestCloudExecutorService.TEXTS;
            if(root.size()!=1 || !root.has("texts") || !root.get("texts").isArray() || root.get("texts").size()!=3) throw error(422,"COMPUTE_V2_WORKLOAD_INVALID");
            var values=new ArrayList<String>();for(var text:root.get("texts")) {
                if(!text.isTextual() || text.textValue().isBlank() || text.textValue().length()>4000) throw error(422,"COMPUTE_V2_WORKLOAD_INVALID");
                values.add(text.textValue());}return List.copyOf(values);
        } catch(java.io.IOException malformed){throw error(422,"COMPUTE_V2_WORKLOAD_INVALID");}
    }
    private byte[] encode(Object value) {try{return json.writeValueAsBytes(value);}catch(java.io.IOException invalid){throw new IllegalArgumentException(invalid);}}
    private String signature(ComputeV2Event event,String purpose) {
        return hmac((purpose+":"+event.taskNo()+":"+event.generation()+":"+event.deviceId()+":"+event.task().inputSha256()).getBytes(StandardCharsets.UTF_8));
    }
    private String hmac(byte[] bytes) {
        try {Mac mac=Mac.getInstance("HmacSHA256");mac.init(new SecretKeySpec(proofSecret,"HmacSHA256"));
            return HexFormat.of().formatHex(mac.doFinal(bytes));
        } catch(GeneralSecurityException unavailable){throw new IllegalStateException(unavailable);}
    }
    private boolean constant(String actual,String expected){return actual!=null&&MessageDigest.isEqual(actual.getBytes(StandardCharsets.UTF_8),expected.getBytes(StandardCharsets.UTF_8));}
    private void requireFleet(Identity identity){cloud.requireCurrent(identity);if(!"fleet".equals(identity.executorId()))throw error(403,"TEST_CLOUD_FLEET_REQUIRED");}
    private String executor(String runner){if(runner==null||!runner.matches("runner-[1-6]"))throw error(422,"COMPUTE_V2_RUNNER_INVALID");return "compute-v2:"+runner;}
    private void enqueue(long deviceId){if(enrolled.contains(deviceId)&&readyDeviceIds.add(deviceId))readyDevices.add(deviceId);}
    private void afterExit(ComputeV2Event event){
        lifecycle.ifPresent(owner->owner.finalizeExited(event.userId(),event.deviceId(),event.taskNo(),event.generation()));
        enqueue(event.deviceId());
    }
    private static Set<Long> ids(String text){var values=new LinkedHashSet<Long>();for(String id:text.split(","))if(!id.isBlank()){
        if(!id.trim().matches("[1-9][0-9]{0,18}"))throw new IllegalArgumentException("COMPUTE_V2_DEVICE_IDS_INVALID");
        long n=Long.parseLong(id.trim());if(n<=0||!values.add(n))throw new IllegalArgumentException("COMPUTE_V2_DEVICE_IDS_INVALID");
    }if(values.size()>6000)throw new IllegalArgumentException("COMPUTE_V2_CAPACITY_EXCEEDED");return Set.copyOf(values);}
    private BizException error(int code,String message){return new BizException(code,message);}
}
